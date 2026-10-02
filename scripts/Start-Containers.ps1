param([switch]$NoBuild, [switch]$ImportNativeData)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    if (!$NoBuild) { docker compose build; if ($LASTEXITCODE -ne 0) { throw 'Container build failed' } }
    if ($ImportNativeData) {
        & (Join-Path $PSScriptRoot 'Stop-Lab.ps1')
        foreach ($taskRuntime in @('csharp','java')) {
            $taskSeed = Join-Path $taskRoot "data\$taskRuntime"
            if (!(Test-Path -LiteralPath (Join-Path $taskSeed 'events.json'))) { continue }
            $taskImage = "atlas-${taskRuntime}:local"
            $taskVolume = "atlas-${taskRuntime}-data"
            docker volume create $taskVolume | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Volume creation failed' }
            docker run --rm --user 0 --volume "${taskVolume}:/data" --mount "type=bind,source=$taskSeed,target=/seed,readonly" --entrypoint sh $taskImage -c 'if [ -e /data/events.json ]; then echo "Existing history retained"; else cp /seed/events.json /data/events.json && chown 10001:10001 /data/events.json; fi'
            if ($LASTEXITCODE -ne 0) { throw 'Initial history import failed' }
        }
    }
    docker compose up --detach --no-build --wait --wait-timeout 120
    if ($LASTEXITCODE -ne 0) { throw 'Container startup failed' }
    foreach ($taskPort in @(5081,5082)) { Invoke-RestMethod "http://127.0.0.1:$taskPort/health" }
} finally { Pop-Location }
