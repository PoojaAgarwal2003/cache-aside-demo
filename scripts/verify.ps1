#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$UnitOnly,
    [ValidateRange(0, 65535)][int]$NativePostgresPort = 0
)
. (Join-Path $PSScriptRoot 'common.ps1')
$null = Get-LabJava
if ($UnitOnly -and $NativePostgresPort) { throw 'Choose -UnitOnly or -NativePostgresPort, not both.' }
if ($NativePostgresPort -ne 0 -and $NativePostgresPort -lt 1024) { throw 'Native test port must be 1024-65535.' }
$previous = $env:FLASHSALE_TEST_JDBC_URL
Push-Location $ProjectRoot
try {
    if ($NativePostgresPort) {
        $env:FLASHSALE_TEST_JDBC_URL = "jdbc:postgresql://127.0.0.1:$NativePostgresPort/flashsale_test"
        Write-Host 'EXPLICIT NATIVE POSTGRESQL acceptance; this is not a Docker/Testcontainers run.'
    } else {
        Remove-Item Env:\FLASHSALE_TEST_JDBC_URL -ErrorAction SilentlyContinue
    }
    if ($UnitOnly) {
        & (Join-Path $ProjectRoot 'gradlew.bat') test bootJar --console=plain
    } else {
        & (Join-Path $ProjectRoot 'gradlew.bat') check bootJar --console=plain
    }
    if ($LASTEXITCODE -ne 0) {
        throw 'Verification failed. Integration tests require Docker Linux containers or an explicitly supplied real local PostgreSQL test database.'
    }
} finally {
    [Environment]::SetEnvironmentVariable('FLASHSALE_TEST_JDBC_URL', $previous, 'Process')
    Pop-Location
}
