param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
function Invoke-TaskDocker {
    param([string[]]$Arguments)
    $taskPreference = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $taskOutput = & docker @Arguments 2>&1; $taskCode = $LASTEXITCODE }
    finally { $ErrorActionPreference = $taskPreference }
    if ($taskCode -ne 0) { throw "Docker failed (exit ${taskCode}): $($taskOutput -join [Environment]::NewLine)" }
    return ($taskOutput -join [Environment]::NewLine)
}
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskSecrets = if ($env:ATLAS_SECRET_DIR) { [IO.Path]::GetFullPath($env:ATLAS_SECRET_DIR) } else { Join-Path $taskRoot '.local\commerce-secrets' }
New-Item -ItemType Directory -Force -Path $taskSecrets | Out-Null
foreach ($taskFile in @('app-password','admin-password')) {
    $taskPath = Join-Path $taskSecrets $taskFile
    if (!(Test-Path -LiteralPath $taskPath)) {
        $taskExistingDatabase = $false
        try { Invoke-TaskDocker @('volume','inspect','atlas-commerce-data') | Out-Null; $taskExistingDatabase = $true } catch {}
        if ($taskExistingDatabase) { throw 'Existing commerce data requires the original secret files; restore them before starting.' }
        $taskBytes = New-Object byte[] 32
        $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
        [IO.File]::WriteAllText($taskPath,([BitConverter]::ToString($taskBytes)).Replace('-','').ToLowerInvariant(),(New-Object Text.UTF8Encoding($false)))
    }
    if ([IO.File]::ReadAllText($taskPath) -notmatch '^[a-f0-9]{64}$') { throw 'Invalid commerce secret file; restore the original key.' }
}
$env:ATLAS_SECRET_DIR = $taskSecrets
Push-Location $taskRoot
try {
    if (!$NoBuild) { docker compose --file compose.yaml --file compose.commerce.yaml build; if ($LASTEXITCODE -ne 0) { throw 'Build failed' } }
    $taskImage = if ($env:COMMERCE_DB_IMAGE) { $env:COMMERCE_DB_IMAGE } else { 'atlas-postgres:local' }
    $taskHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $taskSqlHash = ([BitConverter]::ToString($taskHasher.ComputeHash([IO.File]::ReadAllBytes((Join-Path $taskRoot 'database\init\commerce.sql.in'))))).Replace('-','').ToLowerInvariant()
        $taskInitHash = ([BitConverter]::ToString($taskHasher.ComputeHash([IO.File]::ReadAllBytes((Join-Path $taskRoot 'database\init\01-commerce.sh'))))).Replace('-','').ToLowerInvariant()
    } finally { $taskHasher.Dispose() }
    foreach ($taskRole in @('app','admin')) {
        $taskVolume = "atlas-commerce-$taskRole-secrets"
        Invoke-TaskDocker @('volume','create',$taskVolume) | Out-Null
        $taskCheck = "test -f /secrets/$taskRole-password && chown 0:0 /secrets/$taskRole-password && chmod 0444 /secrets/$taskRole-password && test `$(wc -c < /secrets/$taskRole-password) -eq 64 && test `$(sha256sum /docker-entrypoint-initdb.d/commerce.sql.in | cut -d ' ' -f 1) = '$taskSqlHash' && test `$(sha256sum /docker-entrypoint-initdb.d/01-commerce.sh | cut -d ' ' -f 1) = '$taskInitHash'"
        $taskHelper = Invoke-TaskDocker @('create','--user','0:0','--network','none','--read-only','--cap-drop','ALL','--cap-add','CHOWN','--security-opt','no-new-privileges:true','--volume',"${taskVolume}:/secrets",'--entrypoint','sh',$taskImage,'-c',$taskCheck)
        if ($taskHelper -notmatch '^[a-f0-9]{64}$') { throw 'Invalid secret loader container id' }
        try {
            Invoke-TaskDocker @('cp',(Join-Path $taskSecrets "$taskRole-password"),"${taskHelper}:/secrets/$taskRole-password") | Out-Null
            Invoke-TaskDocker @('start','--attach',$taskHelper) | Out-Null
        } finally { Invoke-TaskDocker @('rm','--force',$taskHelper) | Out-Null }
    }
    docker compose --file compose.yaml --file compose.commerce.yaml up --detach --no-build --wait --wait-timeout 240
    if ($LASTEXITCODE -ne 0) { throw 'Commerce startup failed' }
    foreach ($taskPort in @(5081,5082)) { Invoke-RestMethod "http://127.0.0.1:$taskPort/api/commerce/health" }
} finally { Pop-Location }
