#requires -Version 5.1
[CmdletBinding()]
param([switch]$SkipContainers)
. (Join-Path $PSScriptRoot 'common.ps1')
Import-LabEnvironment
$stateFile = Get-LabStateFile
if (Test-Path -LiteralPath $stateFile) {
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    $owned = Get-Process -Id $state.pid -ErrorAction SilentlyContinue
    if ($owned) {
        if ($owned.StartTime.ToUniversalTime().ToString('o') -ne $state.started -or
            $owned.Path -ne $state.executable) {
            throw 'PID ownership no longer matches; refusing to stop a possibly unrelated process.'
        }
        $state.stopRequested = $true
        $state | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
        Stop-Process -Id $owned.Id -ErrorAction Stop
        $owned.WaitForExit()
    } else {
        Remove-Item -LiteralPath $stateFile
    }
}
if (-not $SkipContainers) { Invoke-LabCompose @('stop') }
Write-Host 'Project app stopped if owned; project database volumes were not deleted.'
