$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'agent-process.ps1')
$controlLock = Open-AgentControlLock
try {
    $runningAgent = Get-ManagedAgent
    if (-not $runningAgent) {
        if (Test-Path -LiteralPath $script:AgentState) { Remove-Item -LiteralPath $script:AgentState }
        Write-Output 'Agent is already stopped.'
        return
    }
    # PID, creation time and exact JAR path were checked above; never stop all Java processes.
    Stop-Process -Id $runningAgent.ProcessId -ErrorAction Stop
    Wait-Process -Id $runningAgent.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $script:AgentState -ErrorAction SilentlyContinue
    Write-Output ('Agent stopped. PID: ' + $runningAgent.ProcessId)
} finally { $controlLock.Dispose() }
