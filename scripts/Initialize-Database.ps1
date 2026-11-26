[CmdletBinding()]
param(
    [ValidatePattern('^[a-z_][a-z0-9_]{0,62}$')]
    [string]$Database = 'govaccess',

    [ValidateRange(1, 65535)]
    [int]$Port = 5432
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:DB_USERNAME) -or [string]::IsNullOrEmpty($env:DB_PASSWORD)) {
    throw 'Set DB_USERNAME and DB_PASSWORD for your PostgreSQL login before running this script.'
}

$psql = (Get-Command psql -CommandType Application -ErrorAction Stop).Path
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$previousPgPassword = $env:PGPASSWORD
$psqlArguments = @('-X', '-w', '-h', '127.0.0.1', '-p', $Port, '-U', $env:DB_USERNAME,
    '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-v', "database_name=$Database")
try {
    $env:PGPASSWORD = $env:DB_PASSWORD
    @'
SELECT format('CREATE DATABASE %I', :'database_name')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database_name')
\gexec
'@ | & $psql @psqlArguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Database provisioning failed. Check the PostgreSQL connection and whether the login has CREATEDB.'
    }
} finally {
    $env:PGPASSWORD = $previousPgPassword
}

$previousDbUrl = $env:DB_URL
$env:DB_URL = "jdbc:postgresql://127.0.0.1:${Port}/$Database"
Push-Location -LiteralPath $repositoryRoot
try {
    & .\mvnw.cmd compile flyway:migrate
    if ($LASTEXITCODE -ne 0) {
        throw 'Flyway migration failed.'
    }
} catch {
    $env:DB_URL = $previousDbUrl
    throw
} finally {
    Pop-Location
}

Write-Output "Database '$Database' is ready. DB_URL is set for this PowerShell session."
