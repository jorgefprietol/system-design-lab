param([ValidateSet('CSharp','Java','Both')][string]$Implementation = 'Both', [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$env:LAB_ROOT = $taskRoot
if ($Implementation -in @('CSharp','Both')) {
    $taskDotnet = Join-Path (Split-Path -Parent $taskRoot) '.tools\dotnet\dotnet.exe'
    if (!(Test-Path -LiteralPath $taskDotnet)) { $taskDotnet = (Get-Command dotnet -ErrorAction Stop).Source }
}
if ($Implementation -in @('Java','Both')) {
    $taskJava = (Get-Command java -ErrorAction Stop).Source
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $taskJava)
}
foreach ($taskPort in @(5081,5082)) {
    if (($Implementation -eq 'CSharp' -and $taskPort -eq 5082) -or ($Implementation -eq 'Java' -and $taskPort -eq 5081)) { continue }
    if (Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue) { throw "Puerto $taskPort ocupado. Detén el laboratorio antes de volver a iniciarlo." }
}
$taskLogs = Join-Path $taskRoot 'data\logs'
New-Item -ItemType Directory -Force -Path $taskLogs | Out-Null
Push-Location $taskRoot
try {
    if ($Implementation -in @('CSharp','Both')) {
        if (!$NoBuild) { & $taskDotnet build csharp/SystemDesignLab.csproj --nologo; if ($LASTEXITCODE -ne 0) { throw 'C# build failed' } }
        $env:LAB_PORT = '5081'; $env:LAB_DATA = Join-Path $taskRoot 'data\csharp'
        $taskDll = Join-Path $taskRoot 'csharp\bin\Debug\net10.0\SystemDesignLab.dll'
        $taskProcess = Start-Process -FilePath $taskDotnet -ArgumentList ('"' + $taskDll + '"') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskLogs 'csharp.out.log') -RedirectStandardError (Join-Path $taskLogs 'csharp.err.log')
        $taskProcess.Id | Set-Content -LiteralPath (Join-Path $taskLogs 'csharp.pid')
    }
    if ($Implementation -in @('Java','Both')) {
        if (!$NoBuild) { mvn -q -f java/pom.xml package; if ($LASTEXITCODE -ne 0) { throw 'Java build failed. Use JDK 21 or newer.' } }
        $env:LAB_PORT = '5082'; $env:LAB_DATA = Join-Path $taskRoot 'data\java'
        $taskJar = Join-Path $taskRoot 'java\target\system-design-lab-1.0.0.jar'
        $taskProcess = Start-Process -FilePath $taskJava -ArgumentList @('-jar',('"' + $taskJar + '"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskLogs 'java.out.log') -RedirectStandardError (Join-Path $taskLogs 'java.err.log')
        $taskProcess.Id | Set-Content -LiteralPath (Join-Path $taskLogs 'java.pid')
    }
    foreach ($taskRuntime in @('csharp','java')) {
        if (($Implementation -eq 'CSharp' -and $taskRuntime -eq 'java') -or ($Implementation -eq 'Java' -and $taskRuntime -eq 'csharp')) { continue }
        $taskPort = if ($taskRuntime -eq 'java') { 5082 } else { 5081 }
        $taskHealthy = $false
        for ($taskAttempt=0; $taskAttempt -lt 30; $taskAttempt++) {
            try { $taskHealth = Invoke-RestMethod "http://127.0.0.1:$taskPort/health"; $taskHealthy = $taskHealth.implementation -eq $taskRuntime; if ($taskHealthy) { break } } catch { }
            Start-Sleep -Milliseconds 200
        }
        if (!$taskHealthy) { throw "Startup failed for $taskRuntime. See $taskLogs" }
        Write-Host "$taskRuntime listo: http://127.0.0.1:$taskPort"
    }
} finally { Pop-Location; Remove-Item Env:LAB_PORT -ErrorAction SilentlyContinue; Remove-Item Env:LAB_DATA -ErrorAction SilentlyContinue }
