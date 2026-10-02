param([ValidateSet('CSharp','Java','Both')][string]$Implementation = 'Both')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
foreach ($taskRuntime in @('csharp','java')) {
    if (($Implementation -eq 'CSharp' -and $taskRuntime -eq 'java') -or ($Implementation -eq 'Java' -and $taskRuntime -eq 'csharp')) { continue }
    $taskPidFile = Join-Path $taskRoot "data\logs\$taskRuntime.pid"
    if (!(Test-Path -LiteralPath $taskPidFile)) { continue }
    $taskProcessId = [int](Get-Content -LiteralPath $taskPidFile)
    $taskProcessInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $taskProcessId"
    if (!$taskProcessInfo) { continue }
    $taskEntry = if ($taskRuntime -eq 'java') { Join-Path $taskRoot 'java\target\system-design-lab-1.0.0.jar' } else { Join-Path $taskRoot 'csharp\bin\Debug\net10.0\SystemDesignLab.dll' }
    if (!$taskProcessInfo.CommandLine.Contains($taskEntry)) { throw "PID $taskProcessId no pertenece a este laboratorio; no se ha detenido." }
    Stop-Process -Id $taskProcessId
    Write-Host "$taskRuntime detenido. El historial permanece en data/$taskRuntime."
}
