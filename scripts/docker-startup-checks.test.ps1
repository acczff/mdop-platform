# Run with Windows PowerShell 5.1. Does not start or stop Docker or modify its files.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'docker-startup-checks.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('mdop-docker-probe-' + [guid]::NewGuid().ToString('N') + '.exe')
$previousMode = $env:MDOP_DOCKER_PROBE_TEST
try {
    Add-Type -OutputAssembly $fixture -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.Threading;
public class DockerProbeFixture {
    public static int Main() {
        string mode = Environment.GetEnvironmentVariable("MDOP_DOCKER_PROBE_TEST");
        if (mode == "empty") return 0;
        if (mode == "timeout") { Thread.Sleep(10000); return 0; }
        if (mode == "malformed") { Console.WriteLine("engine is starting"); return 0; }
        if (mode == "client") { Console.WriteLine("{\"ClientInfo\":{\"Version\":\"29.0\"}}"); return 0; }
        if (mode == "missingVersion") { Console.WriteLine("{\"OSType\":\"linux\"}"); return 0; }
        if (mode == "serverError") { Console.WriteLine("{\"OSType\":\"linux\",\"ServerVersion\":\"29.0\",\"ServerErrors\":[\"not ready\"]}"); return 0; }
        if (mode == "windows") { Console.WriteLine("{\"OSType\":\"windows\",\"ServerVersion\":\"29.0\"}"); return 0; }
        if (mode == "stderr") Console.Error.Write(new string('x', 100000));
        Console.WriteLine("{\"OSType\":\"linux\",\"ServerVersion\":\"29.0\",\"ServerErrors\":[]}");
        return mode == "exitFailure" ? 1 : 0;
    }
}
'@
    foreach ($mode in @('empty','malformed','client','missingVersion','serverError','windows','exitFailure','timeout','ready','stderr')) {
        $env:MDOP_DOCKER_PROBE_TEST = $mode
        $expected = $mode -in @('ready','stderr')
        $actual = Test-DockerEngine -DockerPath $fixture -TimeoutMilliseconds 1000
        if ($actual -ne $expected) { throw "Probe regression failed: $mode; expected $expected, got $actual" }
        Write-Output "PASS $mode"
    }
    $length = 0
    # Load the identity type and independently compare the current process identity.
    $blocked = $false
    try { Assert-UnpackagedDockerStartup } catch { $blocked = $true }
    $isPackaged = [Mdop.DockerStartupIdentity]::GetCurrentPackageFullName([ref]$length, $null) -ne 15700
    if ($blocked -ne $isPackaged) { throw 'Package identity guard regression failed.' }
    Write-Output "PASS package guard (packaged=$isPackaged)"
} finally {
    $env:MDOP_DOCKER_PROBE_TEST = $previousMode
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Force }
}
