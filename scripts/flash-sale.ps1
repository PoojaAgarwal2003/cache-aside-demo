#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('NONE','ATOMIC_SQL','PESSIMISTIC','OPTIMISTIC','REDIS_ASSISTED')][string]$Strategy = 'ATOMIC_SQL',
    [switch]$Compare,
    [ValidateRange(1,100)][int]$Buyers = 50,
    [ValidateRange(1,50)][int]$Concurrency = 10,
    [ValidateRange(0,1000000)][int]$Stock = 10,
    [ValidateRange(1,1000)][int]$Quantity = 1,
    [long]$Seed = 1,
    [ValidateRange(1,120)][int]$DurationSeconds = 60,
    [string]$OutFile,
    [switch]$NoWait
)
$scenario = 'PURCHASE'
if ($Compare) { $scenario = 'COMPARE' }
$json = @{ scenario = $scenario; strategy = $Strategy; buyers = $Buyers; concurrency = $Concurrency;
    stock = $Stock; quantity = $Quantity; seed = $Seed; durationSeconds = $DurationSeconds } | ConvertTo-Json -Compress
& (Join-Path $PSScriptRoot 'fire-requests.ps1') -Json $json -OutFile $OutFile -NoWait:$NoWait
