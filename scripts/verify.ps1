#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$UnitOnly,
    [switch]$BrowserOnly,
    [ValidateRange(0, 65535)][int]$NativePostgresPort = 0,
    [string]$NativeRedisWsl,
    [string]$NativeRedisBinary
)
. (Join-Path $PSScriptRoot 'common.ps1')
$null = Get-LabJava
if ($UnitOnly -and $BrowserOnly) { throw 'Choose -UnitOnly or -BrowserOnly, not both.' }
if ($UnitOnly -and ($NativePostgresPort -or $NativeRedisWsl -or $NativeRedisBinary)) {
    throw 'Choose -UnitOnly or native acceptance prerequisites, not both.'
}
if ($NativePostgresPort -ne 0 -and $NativePostgresPort -lt 1024) { throw 'Native test port must be 1024-65535.' }
if ([bool]$NativeRedisWsl -ne [bool]$NativeRedisBinary) {
    throw 'Supply both -NativeRedisWsl and -NativeRedisBinary, or neither for Testcontainers Redis.'
}
$previous = $env:FLASHSALE_TEST_JDBC_URL
$previousWsl = $env:FLASHSALE_TEST_REDIS_WSL
$previousBinary = $env:FLASHSALE_TEST_REDIS_BINARY
Push-Location $ProjectRoot
try {
    if ($NativePostgresPort) {
        $env:FLASHSALE_TEST_JDBC_URL = "jdbc:postgresql://127.0.0.1:$NativePostgresPort/flashsale_test"
        Write-Host 'EXPLICIT NATIVE POSTGRESQL acceptance; this is not a Docker/Testcontainers run.'
    } else {
        Remove-Item Env:\FLASHSALE_TEST_JDBC_URL -ErrorAction SilentlyContinue
    }
    if ($NativeRedisWsl) {
        $env:FLASHSALE_TEST_REDIS_WSL = $NativeRedisWsl
        $env:FLASHSALE_TEST_REDIS_BINARY = $NativeRedisBinary
        Write-Host 'EXPLICIT real Redis in WSL; tests start/stop only their own foreground Redis processes.'
    } else {
        Remove-Item Env:\FLASHSALE_TEST_REDIS_WSL -ErrorAction SilentlyContinue
        Remove-Item Env:\FLASHSALE_TEST_REDIS_BINARY -ErrorAction SilentlyContinue
    }
    if ($BrowserOnly) {
        if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
            throw 'Browser verification needs Node 22+, npm ci, and npm exec playwright install chromium.'
        }
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw 'Dashboard unit tests failed; run npm ci if pinned dependencies are missing.' }
        & (Join-Path $ProjectRoot 'gradlew.bat') browserTest --console=plain
    } elseif ($UnitOnly) {
        & (Join-Path $ProjectRoot 'gradlew.bat') test bootJar --console=plain
    } else {
        & (Join-Path $ProjectRoot 'gradlew.bat') check bootJar --console=plain
    }
    if ($LASTEXITCODE -ne 0) {
        throw 'Verification failed. Acceptance requires real PostgreSQL AND Redis: Docker Linux containers by default, or explicit native/WSL prerequisites.'
    }
} finally {
    [Environment]::SetEnvironmentVariable('FLASHSALE_TEST_JDBC_URL', $previous, 'Process')
    [Environment]::SetEnvironmentVariable('FLASHSALE_TEST_REDIS_WSL', $previousWsl, 'Process')
    [Environment]::SetEnvironmentVariable('FLASHSALE_TEST_REDIS_BINARY', $previousBinary, 'Process')
    Pop-Location
}
