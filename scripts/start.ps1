#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('demo', 'benchmark', 'default')][string]$Profile = 'demo',
    [switch]$SkipContainers,
    [switch]$NoBuild
)
. (Join-Path $PSScriptRoot 'common.ps1')
Add-Type -AssemblyName System.Net.Http
Import-LabEnvironment
$java = Get-LabJava
$stateFile = Get-LabStateFile
if (Test-Path -LiteralPath $stateFile) { throw 'An app ownership file exists. Run scripts\stop.ps1 first.' }

$probe = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, [int]$env:APP_PORT)
try { $probe.Start() } catch { throw "APP_PORT $env:APP_PORT is occupied. Choose another port; do not stop unrelated processes." }
finally { $probe.Stop() }

Push-Location $ProjectRoot
try {
    if (-not $SkipContainers) { Invoke-LabCompose @('up', '-d', '--wait', '--wait-timeout', '90') }
    if (-not $NoBuild) {
        & (Join-Path $ProjectRoot 'gradlew.bat') bootJar --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Application build failed.' }
    }
    $jar = Join-Path (Get-LabBuildDirectory) 'libs\cache-aside-demo.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'Executable JAR is missing; run without -NoBuild.' }
    New-Item -ItemType Directory -Path (Split-Path $stateFile) -Force | Out-Null
    $process = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$jar`"", "--spring.profiles.active=$Profile") `
        -NoNewWindow -PassThru -WorkingDirectory $ProjectRoot
    $ownership = [guid]::NewGuid().ToString()
    try {
        @{ pid = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o');
           executable = $process.Path; ownership = $ownership; stopRequested = $false } |
            ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
        $ready = $false
        $deadline = [DateTime]::UtcNow.AddSeconds(90)
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($process.HasExited) { throw 'Application exited during startup; see logs\app.log.' }
            try {
                $status = Invoke-RestMethod "http://127.0.0.1:$env:APP_PORT/status" -TimeoutSec 2
                if ($status.application -eq 'FlashSale Lab' -and $status.database -eq 'AVAILABLE') {
                    $ready = $true
                    break
                }
            } catch [System.Net.WebException] {
                # Bounded readiness polling; the final failure is explicit.
            } catch [System.Net.Http.HttpRequestException] {
                # PowerShell 7 uses HttpRequestException for connection failures.
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $ready) { throw 'Application readiness failed after 90 seconds; see logs\app.log.' }
        Write-Host "FlashSale Lab ready at http://127.0.0.1:$env:APP_PORT/status (profile=$Profile)."
        Write-Host 'This terminal owns the app. Ctrl+C or scripts\stop.ps1 stops it; no background load is running.'
        $process.WaitForExit()
        if ($process.ExitCode -ne 0 -and (Test-Path -LiteralPath $stateFile)) {
            $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
            if (-not $state.stopRequested) { throw "Application exited unexpectedly with code $($process.ExitCode)." }
        }
    } finally {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction Stop }
        if ((Test-Path -LiteralPath $stateFile) -and
            (Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json).ownership -eq $ownership) {
            Remove-Item -LiteralPath $stateFile
        }
        $process.Dispose()
    }
} finally { Pop-Location }
