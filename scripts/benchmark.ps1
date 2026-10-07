#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateRange(1, 5)][int]$Warmups = 2,
    [ValidateRange(2, 20)][int]$Trials = 5,
    [ValidateSet('COMPARE', 'READ', 'COLD_WARM', 'STAMPEDE')][string]$Scenario = 'COMPARE',
    [string]$OutDirectory
)
. (Join-Path $PSScriptRoot 'common.ps1')
Import-LabEnvironment
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Benchmark development tooling requires Node 22+; the application does not.' }
$arguments = @((Join-Path $PSScriptRoot 'benchmark.mjs'), '--warmups', "$Warmups", '--trials', "$Trials", '--scenario', $Scenario)
if ($OutDirectory) { $arguments += @('--out', $OutDirectory) }
& node @arguments
if ($LASTEXITCODE -ne 0) { throw 'Benchmark failed; inspect the retained evidence directory and active run before retrying.' }
