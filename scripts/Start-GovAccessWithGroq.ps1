[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$groqBaseUrl = 'https://api.groq.com/openai/v1'
$groqModel = 'openai/gpt-oss-20b'
$groqInput = Read-Host 'Paste your Groq API key' -AsSecureString
if ($groqInput.Length -eq 0) {
    throw 'Enter a Groq API key when prompted.'
}

$previousBaseUrl = $env:AI_BASE_URL
$previousModel = $env:AI_MODEL
$previousApiKey = $env:AI_API_KEY
Push-Location -LiteralPath $projectRoot
try {
    $env:AI_BASE_URL = $groqBaseUrl
    $env:AI_MODEL = $groqModel
    $env:AI_API_KEY = [System.Net.NetworkCredential]::new('', $groqInput).Password.Trim()
    if (-not $env:AI_API_KEY) {
        throw 'Enter a Groq API key when prompted.'
    }

    try {
        $groqModels = Invoke-RestMethod -Uri "$groqBaseUrl/models" -TimeoutSec 15 -Headers @{
            Authorization = "Bearer $env:AI_API_KEY"
        }
    } catch {
        $status = $_.Exception.Response.StatusCode
        if ($null -ne $status) {
            throw "Groq model check returned HTTP $([int]$status). Check your key and its access to $groqModel."
        }
        throw 'Could not reach the Groq API. Check your internet connection and retry.'
    }
    if ($groqModels.data.id -cnotcontains $groqModel) {
        throw "Groq accepted the key, but $groqModel is unavailable to it."
    }

    Write-Host "Groq accepted the key and model. Starting GovAccess with $groqModel."
    & .\mvnw.cmd spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "GovAccess startup exited with code $LASTEXITCODE. See the Maven output above."
    }
} finally {
    Pop-Location
    $env:AI_BASE_URL = $previousBaseUrl
    $env:AI_MODEL = $previousModel
    $env:AI_API_KEY = $previousApiKey
    $groqInput.Dispose()
}
