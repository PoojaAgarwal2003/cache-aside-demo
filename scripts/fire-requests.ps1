#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Json = '{"scenario":"READ"}',
    [string]$OutFile,
    [switch]$NoWait
)
. (Join-Path $PSScriptRoot 'common.ps1')
Import-LabEnvironment
Add-Type -AssemblyName System.Net.Http
$parameters = $Json | ConvertFrom-Json
$seconds = 60
if ($null -ne $parameters.durationSeconds) { $seconds = [int]$parameters.durationSeconds }
if ($seconds -lt 1 -or $seconds -gt 120) { throw 'durationSeconds must be 1-120.' }
$handler = New-Object Net.Http.HttpClientHandler
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$client = New-Object Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(10)
$base = "http://127.0.0.1:$env:APP_PORT"
$run = $null
$finished = $false

function Invoke-RunnerHttp {
    param([string]$Method, [string]$Path, [string]$Body)
    $request = New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::new($Method), "$base$Path")
    try {
        if ($Body) { $request.Content = New-Object Net.Http.StringContent($Body, [Text.Encoding]::UTF8, 'application/json') }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if (-not $response.IsSuccessStatusCode) { throw "Runner HTTP $([int]$response.StatusCode): $text" }
            return $text
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}

try {
    $status = (Invoke-RunnerHttp 'GET' '/status') | ConvertFrom-Json
    if ($status.application -ne 'FlashSale Lab' -or $status.milestone -lt 4) {
        throw 'The loopback port does not serve a milestone-4 FlashSale Lab.'
    }
    $started = Invoke-RunnerHttp 'POST' '/demo/runs' $Json
    $run = ($started | ConvertFrom-Json).runId
    Write-Host "Run $run; inspect /demo/runs/$run or cancel with POST /demo/runs/$run/cancel."
    if ($NoWait) { $finished = $true; Write-Output $started; return }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $snapshot = (Invoke-RunnerHttp 'GET' "/demo/runs/$run") | ConvertFrom-Json
        if (-not $snapshot.active) { $finished = $true; break }
        if ($watch.Elapsed.TotalSeconds -gt ($seconds + 90)) {
            throw "Runner exceeded its dispatch/drain allowance. Run $run remains inspectable."
        }
        Start-Sleep -Milliseconds 500
    } while ($true)
    $export = Invoke-RunnerHttp 'GET' "/demo/runs/$run/export"
    if ($OutFile) {
        [IO.File]::WriteAllText($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutFile),
            $export, (New-Object Text.UTF8Encoding($false)))
        Write-Host "Persisted export written to $OutFile."
    } else { Write-Output $export }
    Write-Host "State: $($snapshot.state); inventory: $($snapshot.result.invariantVerdict)."
    if ($snapshot.state -in @('FAILED', 'INCONCLUSIVE', 'INTERRUPTED')) {
        throw "Run $run did not complete conclusively; inspect its persisted export."
    }
} finally {
    if ($run -and -not $finished) {
        try { Invoke-RunnerHttp 'POST' "/demo/runs/$run/cancel" | Out-Null }
        catch { Write-Warning "Cancellation could not be confirmed for run $run. Inspect the run before starting more work." }
    }
    $client.Dispose()
    $handler.Dispose()
}
