# Read-only checks shared by the manual launcher and its regression tests.
function Assert-UnpackagedDockerStartup {
    if (-not ('Mdop.DockerStartupIdentity' -as [type])) {
        Add-Type @'
using System.Runtime.InteropServices;
using System.Text;
namespace Mdop {
    public static class DockerStartupIdentity {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        public static extern int GetCurrentPackageFullName(ref int length, StringBuilder name);
    }
}
'@
    }
    $length = 0
    $result = [Mdop.DockerStartupIdentity]::GetCurrentPackageFullName([ref]$length, $null)
    if ($result -ne 15700) {
        throw 'Docker startup requires a normal Windows terminal, outside packaged apps such as Codex. AppData and registry redirection can make Docker files invisible to WSL. No startup or recovery was performed.'
    }
}

function Test-DockerEngine {
    param([Parameter(Mandatory)] [string] $DockerPath, [int] $TimeoutMilliseconds = 5000)
    $probe = $null
    try {
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = $DockerPath
        $info.Arguments = 'info --format "{{json .}}"'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $probe = [Diagnostics.Process]::Start($info)
        # Drain both pipes concurrently, including CLI errors during engine startup.
        $stdout = $probe.StandardOutput.ReadToEndAsync()
        $stderr = $probe.StandardError.ReadToEndAsync()
        if (-not $probe.WaitForExit($TimeoutMilliseconds)) { $probe.Kill(); return $false }
        if ($probe.ExitCode -ne 0) { return $false }
        $server = $stdout.GetAwaiter().GetResult() | ConvertFrom-Json -ErrorAction Stop
        return ($server.OSType -eq 'linux' -and -not [string]::IsNullOrWhiteSpace($server.ServerVersion) -and @($server.ServerErrors).Where({ $_ }).Count -eq 0)
    } catch {
        return $false
    } finally {
        if ($probe) { $probe.Dispose() }
    }
}
