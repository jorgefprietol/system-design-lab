$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskDotnet = Join-Path (Split-Path -Parent $taskRoot) '.tools\dotnet\dotnet.exe'
if (!(Test-Path -LiteralPath $taskDotnet)) { $taskDotnet = (Get-Command dotnet -ErrorAction Stop).Source }
$env:LAB_DOTNET = $taskDotnet
$taskJava = (Get-Command java -ErrorAction Stop).Source
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $taskJava)
Push-Location $taskRoot
try {
    & $taskDotnet build csharp/SystemDesignLab.csproj --nologo
    if ($LASTEXITCODE -ne 0) { throw 'C# build failed' }
    mvn -q -f java/pom.xml package
    if ($LASTEXITCODE -ne 0) { throw 'Java build failed' }
    node tests/contract.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Contract tests failed' }
} finally { Pop-Location }
