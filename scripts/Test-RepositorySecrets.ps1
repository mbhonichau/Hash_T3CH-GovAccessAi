[CmdletBinding(DefaultParameterSetName = 'Revision')]
param(
    [Parameter(ParameterSetName = 'Revision')]
    [string]$Revision = 'HEAD',
    [Parameter(Mandatory, ParameterSetName = 'Staged')]
    [switch]$Staged,
    [Parameter(Mandatory, ParameterSetName = 'WorkingTree')]
    [switch]$WorkingTree
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$patterns = @(
    'gsk_[A-Za-z0-9]{30,}',
    'gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}',
    '(AKIA|ASIA)[A-Z0-9]{16}',
    'AIza[0-9A-Za-z_-]{35}',
    '-----BEGIN ([A-Z]+ )*PRIVATE KEY-----',
    '(SUPABASE_DB_PASSWORD|DB_PASSWORD|AI_API_KEY):[^}[:space:]]',
    '^[[:space:]]*(password|api-key):[[:space:]]+["'']?[^$"''#[:space:]][^[:space:]]+',
    'jdbc:postgresql://[^[:space:]@]+:[^[:space:]@]+@'
)
$gitArguments = @('-C', $root, 'grep', '-I', '-l', '-E')
foreach ($pattern in $patterns) {
    $gitArguments += @('-e', $pattern)
}
if ($Staged) {
    $gitArguments += '--cached'
} elseif (-not $WorkingTree) {
    $commitId = git -C $root rev-parse --verify --end-of-options "$Revision^{commit}"
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot resolve the requested Git revision.'
    }
    $gitArguments += $commitId
}
$matchesFound = @(& git @gitArguments --)
switch ($LASTEXITCODE) {
    0 {
        $matchesFound | Write-Output
        throw 'Possible committed secrets detected. Only file paths were printed.'
    }
    1 { Write-Output 'No matching credentials detected in the selected Git snapshot.' }
    default { throw 'Git secret scan failed.' }
}
