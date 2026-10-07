#requires -Version 5.1
[CmdletBinding()]
param([string]$OutDirectory)
. (Join-Path $PSScriptRoot 'common.ps1')
$null = Get-LabJava
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Walkthrough development tooling requires Node 22+.' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'This gate requires real Docker Linux containers and Compose v2.' }
Push-Location $ProjectRoot
try {
    & (Join-Path $ProjectRoot 'gradlew.bat') bootJar --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Walkthrough JAR build failed.' }
    $arguments = @((Join-Path $PSScriptRoot 'walkthrough.mjs'))
    if ($OutDirectory) { $arguments += @('--out', $OutDirectory) }
    & node @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Compose walkthrough failed; inspect its evidence directory. Missing Docker is not a pass.' }
} finally { Pop-Location }
