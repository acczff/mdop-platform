[CmdletBinding()]
param(
    [switch] $RecoverStaleSockets,
    [switch] $NoDashboard,
    [ValidateRange(10, 180)] [int] $TimeoutSeconds = 60
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'docker-startup-checks.ps1')
# Must run before any registry lookup, AppData write, or child process launch.
Assert-UnpackagedDockerStartup
$install = Get-ItemProperty 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Docker Desktop' -ErrorAction SilentlyContinue
if (-not $install) {
    $install = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Docker Desktop' -ErrorAction Stop
}
$installRoot = [IO.Path]::GetFullPath($install.InstallLocation)
$launcher = Join-Path $installRoot 'Docker Desktop.exe'
$docker = Join-Path $installRoot 'resources\bin\docker.exe'
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf) -or -not (Test-Path -LiteralPath $docker -PathType Leaf)) {
    throw 'Docker installation registration does not point to valid executables. Repair the installation first.'
}
$logRoot = Join-Path $env:LOCALAPPDATA 'DockerLauncher'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$lockPath = Join-Path $logRoot 'startup.lock'
try {
    $lock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None')
} catch {
    throw 'Another Docker startup check is in progress. Wait for it to finish.'
}
function Test-Engine {
    Test-DockerEngine -DockerPath $docker
}
function Docker-Processes {
    @(Get-Process -Name 'Docker Desktop','com.docker.backend','com.docker.build','com.docker.service','docker-desktop' -ErrorAction SilentlyContinue)
}
try {
    if (Test-Engine) {
        Write-Output 'Docker Engine is already ready; no restart or runtime recovery performed.'
        if (-not $NoDashboard) { Start-Process -FilePath $launcher -WorkingDirectory $installRoot -WindowStyle Hidden }
        return
    }
    $running = Docker-Processes
    if ($running.Count -gt 0) {
        Write-Output 'Docker is already starting or unhealthy; waiting without stopping it or changing runtime files.'
    } else {
        if ($RecoverStaleSockets) {
            $wslRunning = ((& wsl.exe --list --running --quiet 2>$null) -join '') -replace "\x00", ''
            if ($LASTEXITCODE -ne 0) { throw 'Cannot verify WSL state; no runtime recovery performed.' }
            if ($wslRunning -match 'docker-desktop') { throw 'Docker WSL is still running. Stop Docker cleanly before runtime recovery.' }
            $localRoot = [IO.Path]::GetFullPath($env:LOCALAPPDATA).TrimEnd('\')
            $runtimePaths = @((Join-Path $localRoot 'Docker\run'), (Join-Path $localRoot 'docker-secrets-engine'))
            # Validate both directories before moving either. Never traverse persistent Docker data.
            foreach ($path in $runtimePaths) {
                $resolved = [IO.Path]::GetFullPath($path)
                if (-not $resolved.StartsWith($localRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Runtime path is outside LocalAppData.' }
                if (-not (Test-Path -LiteralPath $resolved)) { continue }
                $item = Get-Item -LiteralPath $resolved
                if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Unexpected runtime directory: $resolved" }
                $contents = @(Get-ChildItem -LiteralPath $resolved -Force)
                foreach ($entry in $contents) {
                    if ($entry.PSIsContainer -or $entry.Length -ne 0 -or -not ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                        throw "Unexpected runtime content; manual review required: $($entry.FullName)"
                    }
                }
            }
            if ((Docker-Processes).Count -gt 0 -or (Test-Engine)) { throw 'Docker started concurrently; runtime recovery cancelled.' }
            foreach ($path in $runtimePaths) {
                if ((Test-Path -LiteralPath $path) -and @(Get-ChildItem -LiteralPath $path -Force).Count -gt 0) {
                    $backup = $path + '-preserved-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
                    Move-Item -LiteralPath $path -Destination $backup
                    Write-Output "Preserved stale runtime directory: $backup"
                }
                if (-not (Test-Path -LiteralPath $path)) { New-Item -ItemType Directory -Path $path | Out-Null }
            }
        }
        # This installation resolves its backend relative to the working directory.
        # Missing/incorrect working directory falls back to an absent legacy registry key.
        Start-Process -FilePath $launcher -WorkingDirectory $installRoot -WindowStyle Hidden
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Engine) { Write-Output 'Docker Engine is ready.'; return }
        Start-Sleep -Seconds 2
    }
    throw 'Docker did not become ready. Inspect Docker Desktop logs; no process was killed and no persistent data was removed.'
} catch {
    Add-Content -LiteralPath (Join-Path $logRoot 'startup-errors.log') -Value ("{0:o} {1}" -f (Get-Date), $_.Exception.Message)
    throw
} finally {
    $lock.Dispose()
}
