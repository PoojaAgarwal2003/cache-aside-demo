$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

function Import-LabEnvironment {
    $allowed = @('APP_PORT', 'POSTGRES_PORT', 'REDIS_PORT', 'DB_PASSWORD')
    $file = Join-Path $ProjectRoot '.env'
    if (Test-Path -LiteralPath $file) {
        foreach ($line in Get-Content -LiteralPath $file) {
            $line = $line.Trim()
            if (-not $line -or $line.StartsWith('#')) { continue }
            if ($line -notmatch '^([A-Z_]+)=(.*)$' -or $allowed -notcontains $Matches[1]) {
                throw '.env must contain only APP_PORT, POSTGRES_PORT, REDIS_PORT and DB_PASSWORD assignments.'
            }
            $name = $Matches[1]
            $value = $Matches[2].Trim()
            if ($value -match '^"(.*)"$' -or $value -match "^'(.*)'$") { $value = $Matches[1] }
            if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
    $defaults = @{ APP_PORT = '8080'; POSTGRES_PORT = '55432'; REDIS_PORT = '56379' }
    foreach ($name in $defaults.Keys) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (-not $value) { $value = $defaults[$name] }
        $port = 0
        if (-not [int]::TryParse($value, [ref]$port) -or $port -lt 1024 -or $port -gt 65535) {
            throw "$name must be an integer port from 1024 through 65535."
        }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Get-LabJava {
    if ($env:JAVA_HOME) {
        $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $java)) { throw 'JAVA_HOME must point to a JDK 17 installation.' }
        return $java
    }
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) { throw 'Install JDK 17 and set JAVA_HOME before running FlashSale Lab.' }
    return $java.Source
}

function Invoke-LabCompose {
    param([string[]]$ComposeArguments)
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker CLI is missing. Install Docker with Linux containers and Compose v2.'
    }
    & docker compose --project-name flashsale-lab --project-directory $ProjectRoot `
        -f (Join-Path $ProjectRoot 'docker-compose.yml') @ComposeArguments
    if ($LASTEXITCODE -ne 0) { throw 'Project Docker Compose command failed; check Docker and port availability.' }
}

function Get-LabBuildDirectory {
    if ($env:FLASHSALE_BUILD_DIR) {
        if ([IO.Path]::IsPathRooted($env:FLASHSALE_BUILD_DIR)) { return $env:FLASHSALE_BUILD_DIR }
        return Join-Path $ProjectRoot $env:FLASHSALE_BUILD_DIR
    }
    return Join-Path $ProjectRoot 'build'
}

function Get-LabStateFile {
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($ProjectRoot.ToLowerInvariant())
        $identity = ([BitConverter]::ToString($hash.ComputeHash($bytes))).Replace('-', '').Substring(0, 16)
        $local = [Environment]::GetFolderPath('LocalApplicationData')
        return Join-Path $local "FlashSaleLab\state\$identity\app.json"
    } finally { $hash.Dispose() }
}
