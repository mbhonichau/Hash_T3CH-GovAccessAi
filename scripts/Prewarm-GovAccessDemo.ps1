[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
if (-not $env:AI_BASE_URL -or -not $env:AI_MODEL) {
    throw 'Set AI_BASE_URL and AI_MODEL for the live provider. Set AI_API_KEY when the provider requires a key.'
}
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
try {
    & .\mvnw.cmd -o spring-boot:run '-Dspring-boot.run.arguments=--server.port=0 --govaccess.ai.cache-mode=RECORD --govaccess.ai.prewarm=true'
    if ($LASTEXITCODE -ne 0) {
        throw 'Demo cache prewarming failed. Check the startup and AI request logs for the outcome.'
    }
} finally {
    Pop-Location
}
