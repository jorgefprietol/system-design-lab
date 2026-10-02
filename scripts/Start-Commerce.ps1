param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskSecrets = if ($env:ATLAS_SECRET_DIR) { [IO.Path]::GetFullPath($env:ATLAS_SECRET_DIR) } else { Join-Path $taskRoot '.local\commerce-secrets' }
New-Item -ItemType Directory -Force -Path $taskSecrets | Out-Null
foreach ($taskFile in @('app-password','admin-password')) {
    $taskPath = Join-Path $taskSecrets $taskFile
    if (!(Test-Path -LiteralPath $taskPath)) {
        $taskBytes = New-Object byte[] 32
        $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
        [IO.File]::WriteAllText($taskPath,([BitConverter]::ToString($taskBytes)).Replace('-','').ToLowerInvariant(),(New-Object Text.UTF8Encoding($false)))
    }
}
$env:ATLAS_SECRET_DIR = $taskSecrets
Push-Location $taskRoot
try {
    if (!$NoBuild) { docker compose --file compose.yaml --file compose.commerce.yaml build; if ($LASTEXITCODE -ne 0) { throw 'Build failed' } }
    docker compose --file compose.yaml --file compose.commerce.yaml up --detach --no-build --wait --wait-timeout 240
    if ($LASTEXITCODE -ne 0) { throw 'Commerce startup failed' }
    foreach ($taskPort in @(5081,5082)) { Invoke-RestMethod "http://127.0.0.1:$taskPort/api/commerce/health" }
} finally { Pop-Location }
