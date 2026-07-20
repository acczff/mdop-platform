[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('verify', 'start', 'status', 'stop')]
    [string] $Action = 'verify'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$backendWrapper = Join-Path $repoRoot 'backend\mvnw.cmd'
$backendPom = Join-Path $repoRoot 'backend\pom.xml'
$frontendRoot = Join-Path $repoRoot 'frontend'
$composeFile = Join-Path $repoRoot 'deploy\compose\compose.local.yml'
$envFile = Join-Path $repoRoot 'deploy\env\.env.local'
$stateFile = Join-Path $repoRoot 'tmp\mdop-local-state.json'
$logDirectory = Join-Path $repoRoot 'logs\local'
$frontendAppRoot = Join-Path $frontendRoot 'apps\admin'
$frontendViteScript = Join-Path $frontendAppRoot 'node_modules\vite\bin\vite.js'
$backendPort = 8080
$frontendPort = 5173

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Description,

        [Parameter(Mandatory = $true)]
        [string] $FilePath,

        [Parameter(Mandatory = $true)]
        [string[]] $ArgumentList
    )

    Write-Host "==> $Description"
    & $FilePath @ArgumentList

    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$Description failed with exit code $exitCode."
    }
}

function Import-DotEnv {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Environment file not found: $Path"
    }

    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $trimmedLine = $line.Trim()

        if ([string]::IsNullOrWhiteSpace($trimmedLine) -or $trimmedLine.StartsWith('#')) {
            continue
        }

        $separatorIndex = $line.IndexOf('=')

        if ($separatorIndex -le 0) {
            throw "Invalid environment entry in $Path."
        }

        $name = $line.Substring(0, $separatorIndex).Trim()
        $value = $line.Substring($separatorIndex + 1).Trim()

        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            throw "Invalid environment variable name in $Path."
        }

        if ($value.Length -ge 2) {
            $quotedWithDouble = $value.StartsWith('"') -and $value.EndsWith('"')
            $quotedWithSingle = $value.StartsWith("'") -and $value.EndsWith("'")

            if ($quotedWithDouble -or $quotedWithSingle) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }

        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Assert-PortAvailable {
    param(
        [Parameter(Mandatory = $true)]
        [int] $Port,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    $listeners = @(
        Get-NetTCPConnection `
            -State Listen `
            -LocalPort $Port `
            -ErrorAction SilentlyContinue
    )

    if ($listeners.Count -gt 0) {
        throw "$Description port $Port is already in use."
    }
}

function Wait-HttpReady {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Uri,

        [Parameter(Mandatory = $true)]
        [System.Diagnostics.Process] $Process,

        [Parameter(Mandatory = $true)]
        [int] $TimeoutSeconds,

        [Parameter(Mandatory = $true)]
        [string] $Description,

        [Parameter(Mandatory = $true)]
        [string[]] $LogPaths
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)

    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()

        if ($Process.HasExited) {
            throw "$Description exited before becoming ready. Logs: $($LogPaths -join ', ')"
        }

        try {
            $response = Invoke-WebRequest `
                -Uri $Uri `
                -UseBasicParsing `
                -TimeoutSec 5

            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                Write-Host "==> $Description is ready."
                return
            }
        }
        catch {
            # The service may still be starting.
        }

        Start-Sleep -Seconds 2
    }

    throw "$Description did not become ready within $TimeoutSeconds seconds. Logs: $($LogPaths -join ', ')"
}

function Get-ManagedProcessInfo {
    param(
        [Parameter(Mandatory = $true)]
        [object] $State,

        [Parameter(Mandatory = $true)]
        [string] $ProcessName
    )

    $entryProperty = $State.PSObject.Properties[$ProcessName]

    if ($null -eq $entryProperty -or $null -eq $entryProperty.Value) {
        return [pscustomobject]@{
            Status = 'no state'
            Process = $null
        }
    }

    $entry = $entryProperty.Value
    $processIdProperty = $entry.PSObject.Properties['processId']
    $startTimeProperty = $entry.PSObject.Properties['startTimeUtcTicks']

    if ($null -eq $processIdProperty -or $null -eq $startTimeProperty) {
        return [pscustomobject]@{
            Status = 'invalid state'
            Process = $null
        }
    }

    try {
        $processId = [int] $processIdProperty.Value
        $expectedStartTimeUtcTicks = [long] $startTimeProperty.Value
    }
    catch {
        return [pscustomobject]@{
            Status = 'invalid state'
            Process = $null
        }
    }

    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue

    if ($null -eq $process) {
        return [pscustomobject]@{
            Status = 'stopped'
            Process = $null
        }
    }

    try {
        $actualStartTimeUtcTicks = $process.StartTime.ToUniversalTime().Ticks
    }
    catch {
        return [pscustomobject]@{
            Status = 'stale state'
            Process = $null
        }
    }

    if ($actualStartTimeUtcTicks -ne $expectedStartTimeUtcTicks) {
        return [pscustomobject]@{
            Status = 'stale state'
            Process = $null
        }
    }

    return [pscustomobject]@{
        Status = 'running'
        Process = $process
    }
}

function Show-MdopStatus {
    if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
        throw "Compose file not found: $composeFile"
    }

    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        throw "Local environment file not found: $envFile"
    }

    $dockerCommand = (Get-Command 'docker.exe' -ErrorAction Stop).Source

    Write-Host '==> Infrastructure status'
    & $dockerCommand compose --env-file $envFile -f $composeFile ps --all

    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Docker Compose status failed with exit code $exitCode."
    }

    Write-Host '==> Managed application status'

    if (-not (Test-Path -LiteralPath $stateFile -PathType Leaf)) {
        Write-Host 'No managed backend or frontend processes.'
        return
    }

    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json

    foreach ($processName in @('backend', 'frontend')) {
        $info = Get-ManagedProcessInfo -State $state -ProcessName $processName

        if ($info.Status -eq 'running') {
            $processId = $info.Process.Id
            Write-Host "${processName}: running (PID $processId)"
        }
        else {
            Write-Host "${processName}: $($info.Status)"
        }
    }
}

function Stop-Mdop {
    $errors = @()
    $state = $null

    if (Test-Path -LiteralPath $stateFile -PathType Leaf) {
        try {
            $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
        }
        catch {
            $errors += "Cannot read runtime state: $($_.Exception.Message)"
        }
    }

    if ($null -ne $state) {
        foreach ($processName in @('frontend', 'backend')) {
            $info = Get-ManagedProcessInfo -State $state -ProcessName $processName

            if ($info.Status -ne 'running') {
                Write-Host "${processName}: $($info.Status)"
                continue
            }

            $processId = $info.Process.Id
            Write-Host "==> Stopping $processName (PID $processId)"

            try {
                Stop-Process -Id $processId -Force -ErrorAction Stop

                if (-not $info.Process.WaitForExit(10000)) {
                    throw "$processName did not stop."
                }
            }
            catch {
                if ($null -ne (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
                    $errors += "Cannot stop ${processName}: $($_.Exception.Message)"
                }
            }
        }
    }
    else {
        Write-Host 'No managed backend or frontend processes.'
    }

    try {
        if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
            throw "Compose file not found: $composeFile"
        }

        if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
            throw "Local environment file not found: $envFile"
        }

        $dockerCommand = (Get-Command 'docker.exe' -ErrorAction Stop).Source

        Write-Host '==> Stopping infrastructure'
        & $dockerCommand compose --env-file $envFile -f $composeFile stop

        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            throw "Docker Compose stop failed with exit code $exitCode."
        }
    }
    catch {
        $errors += $_.Exception.Message
    }

    if ($errors.Count -gt 0) {
        throw ($errors -join [Environment]::NewLine)
    }

    if (Test-Path -LiteralPath $stateFile -PathType Leaf) {
        Remove-Item -LiteralPath $stateFile -Force
    }

    Write-Host '==> Repository services stopped.'
}

function Stop-StartedProcess {
    param(
        [Parameter(Mandatory = $true)]
        [System.Diagnostics.Process] $Process,

        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    try {
        $Process.Refresh()

        if ($Process.HasExited) {
            return
        }

        $processId = $Process.Id
        Stop-Process -Id $processId -Force -ErrorAction Stop

        if (-not $Process.WaitForExit(10000)) {
            Write-Warning "$Description process $processId did not stop."
        }
    }
    catch {
        Write-Warning "Cannot stop ${Description}: $($_.Exception.Message)"
    }
}

function Start-Mdop {
    if (Test-Path -LiteralPath $stateFile -PathType Leaf) {
        try {
            $existingState = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
        }
        catch {
            throw "Cannot read existing runtime state: $($_.Exception.Message)"
        }

        foreach ($processName in @('backend', 'frontend')) {
            $info = Get-ManagedProcessInfo `
                -State $existingState `
                -ProcessName $processName

            if ($info.Status -eq 'running') {
                throw "$processName is already running. Run '.\mdop.cmd status' first."
            }

            if ($info.Status -eq 'invalid state') {
                throw "Runtime state for $processName is invalid."
            }
        }

        Remove-Item -LiteralPath $stateFile -Force
    }

    foreach ($requiredFile in @($backendWrapper, $backendPom, $composeFile, $envFile)) {
        if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
            throw "Required file not found: $requiredFile"
        }
    }

    $dockerCommand = (Get-Command 'docker.exe' -ErrorAction Stop).Source
    $javaCommand = (Get-Command 'java.exe' -ErrorAction Stop).Source
    $nodeCommand = (Get-Command 'node.exe' -ErrorAction Stop).Source
    $pnpmCommand = (Get-Command 'pnpm.cmd' -ErrorAction Stop).Source

    Import-DotEnv -Path $envFile

    foreach (
        $requiredVariable in @(
            'MDOP_MYSQL_PORT',
            'MDOP_MYSQL_DATABASE',
            'MDOP_MYSQL_USER',
            'MDOP_MYSQL_PASSWORD'
        )
    ) {
        $value = [Environment]::GetEnvironmentVariable(
            $requiredVariable,
            'Process'
        )

        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "Required environment variable is not set: $requiredVariable"
        }
    }

    Assert-PortAvailable -Port $backendPort -Description 'Backend'
    Assert-PortAvailable -Port $frontendPort -Description 'Frontend'

    $null = & $dockerCommand version --format '{{.Server.Version}}'
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Engine is not available.'
    }

    Invoke-CheckedCommand `
        -Description 'Validating Docker Compose configuration' `
        -FilePath $dockerCommand `
        -ArgumentList @(
            'compose',
            '--env-file',
            $envFile,
            '-f',
            $composeFile,
            'config',
            '--quiet'
        )

    Invoke-CheckedCommand `
        -Description 'Preparing frontend dependencies' `
        -FilePath $pnpmCommand `
        -ArgumentList @(
            '--dir',
            $frontendRoot,
            'install',
            '--frozen-lockfile'
        )

    Invoke-CheckedCommand `
        -Description 'Packaging backend application' `
        -FilePath $backendWrapper `
        -ArgumentList @(
            '-f',
            $backendPom,
            '-pl',
            'mdop-boot',
            '-am',
            '-DskipTests',
            'package'
        )

    if (-not (Test-Path -LiteralPath $frontendViteScript -PathType Leaf)) {
        throw "Vite entry script not found: $frontendViteScript"
    }

    $backendTargetDirectory = Join-Path $repoRoot 'backend\mdop-boot\target'
    $backendJar = Get-ChildItem `
        -LiteralPath $backendTargetDirectory `
        -Filter 'mdop-boot-*.jar' |
        Where-Object {
            $_.Name -notmatch '-(sources|javadoc)\.jar$'
        } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1

    if ($null -eq $backendJar) {
        throw "Backend executable JAR not found in $backendTargetDirectory."
    }

    $runningServicesBefore = @(
        & $dockerCommand `
            compose `
            --env-file $envFile `
            -f $composeFile `
            ps `
            --status running `
            --services
    )

    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot inspect running Compose services.'
    }

    $allServices = @(
        & $dockerCommand `
            compose `
            --env-file $envFile `
            -f $composeFile `
            config `
            --services
    )

    if ($LASTEXITCODE -ne 0 -or $allServices.Count -eq 0) {
        throw 'Cannot resolve Compose services.'
    }

    $servicesStartedByThisRun = @(
        $allServices |
            Where-Object {
                $runningServicesBefore -notcontains $_
            }
    )

    $stateDirectory = Split-Path -Parent $stateFile
    $null = New-Item -ItemType Directory -Path $stateDirectory -Force
    $null = New-Item -ItemType Directory -Path $logDirectory -Force

    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backendStandardOutput = Join-Path $logDirectory "$timestamp-backend.out.log"
    $backendStandardError = Join-Path $logDirectory "$timestamp-backend.err.log"
    $frontendStandardOutput = Join-Path $logDirectory "$timestamp-frontend.out.log"
    $frontendStandardError = Join-Path $logDirectory "$timestamp-frontend.err.log"

    $backendProcess = $null
    $frontendProcess = $null

    try {
        Invoke-CheckedCommand `
            -Description 'Starting infrastructure' `
            -FilePath $dockerCommand `
            -ArgumentList @(
                'compose',
                '--env-file',
                $envFile,
                '-f',
                $composeFile,
                'up',
                '-d',
                '--wait',
                '--wait-timeout',
                '120'
            )

        $backendArguments = @(
            '-jar',
            "`"$($backendJar.FullName)`"",
            '--spring.profiles.active=local',
            '--server.address=127.0.0.1',
            "--server.port=$backendPort"
        )

        Write-Host '==> Starting backend'
        $backendProcess = Start-Process `
            -FilePath $javaCommand `
            -ArgumentList $backendArguments `
            -WorkingDirectory $repoRoot `
            -RedirectStandardOutput $backendStandardOutput `
            -RedirectStandardError $backendStandardError `
            -WindowStyle Hidden `
            -PassThru

        Wait-HttpReady `
            -Uri "http://127.0.0.1:$backendPort/actuator/health" `
            -Process $backendProcess `
            -TimeoutSeconds 120 `
            -Description 'Backend' `
            -LogPaths @($backendStandardOutput, $backendStandardError)

        $frontendArguments = @(
            "`"$frontendViteScript`"",
            '--host',
            '127.0.0.1',
            '--port',
            $frontendPort,
            '--strictPort'
        )

        Write-Host '==> Starting frontend'
        $frontendProcess = Start-Process `
            -FilePath $nodeCommand `
            -ArgumentList $frontendArguments `
            -WorkingDirectory $frontendAppRoot `
            -RedirectStandardOutput $frontendStandardOutput `
            -RedirectStandardError $frontendStandardError `
            -WindowStyle Hidden `
            -PassThru

        Wait-HttpReady `
            -Uri "http://127.0.0.1:$frontendPort/" `
            -Process $frontendProcess `
            -TimeoutSeconds 60 `
            -Description 'Frontend' `
            -LogPaths @($frontendStandardOutput, $frontendStandardError)

        $runtimeState = [ordered]@{
            schemaVersion = 1
            startedAtUtc = [DateTime]::UtcNow.ToString('o')
            backend = [ordered]@{
                processId = $backendProcess.Id
                startTimeUtcTicks = $backendProcess.StartTime.ToUniversalTime().Ticks
                standardOutputLog = $backendStandardOutput
                standardErrorLog = $backendStandardError
            }
            frontend = [ordered]@{
                processId = $frontendProcess.Id
                startTimeUtcTicks = $frontendProcess.StartTime.ToUniversalTime().Ticks
                standardOutputLog = $frontendStandardOutput
                standardErrorLog = $frontendStandardError
            }
        }

        $runtimeState |
            ConvertTo-Json -Depth 4 |
            Set-Content -LiteralPath $stateFile -Encoding UTF8

        Write-Host '==> Repository services started.'
        Write-Host "Backend: http://127.0.0.1:$backendPort"
        Write-Host "Frontend: http://127.0.0.1:$frontendPort"
        Write-Host "Logs: $logDirectory"
    }
    catch {
        $startupError = $_.Exception.Message
        Write-Warning "Startup failed: $startupError"

        if ($null -ne $frontendProcess) {
            Stop-StartedProcess `
                -Process $frontendProcess `
                -Description 'frontend'
        }

        if ($null -ne $backendProcess) {
            Stop-StartedProcess `
                -Process $backendProcess `
                -Description 'backend'
        }

        if ($servicesStartedByThisRun.Count -gt 0) {
            $stopArguments = @(
                'compose',
                '--env-file',
                $envFile,
                '-f',
                $composeFile,
                'stop'
            )
            $stopArguments += $servicesStartedByThisRun

            & $dockerCommand @stopArguments

            if ($LASTEXITCODE -ne 0) {
                Write-Warning 'Cannot fully restore infrastructure state.'
            }
        }

        if (Test-Path -LiteralPath $stateFile -PathType Leaf) {
            Remove-Item -LiteralPath $stateFile -Force
        }

        throw $startupError
    }
}

try {
    switch ($Action) {

        'status' {
            Show-MdopStatus
        }

        'verify' {
            $pnpmCommand = (Get-Command 'pnpm.cmd' -ErrorAction Stop).Source

            Invoke-CheckedCommand `
                -Description 'Backend Maven verify' `
                -FilePath $backendWrapper `
                -ArgumentList @('-f', $backendPom, 'verify')

            Invoke-CheckedCommand `
                -Description 'Frontend pnpm verify' `
                -FilePath $pnpmCommand `
                -ArgumentList @('--dir', $frontendRoot, 'run', 'verify')

            Write-Host '==> Repository verification succeeded.'
        }

        'start' {
            Start-Mdop
        }

        'stop' {
            Stop-Mdop
        }
    }
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
