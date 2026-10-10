param([switch]$Rebuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'agent-process.ps1')
$controlLock = Open-AgentControlLock
Push-Location -LiteralPath $PSScriptRoot
try {
    $runningAgent = Get-ManagedAgent
    if ($runningAgent) {
        Write-Output ('Agent is already running. PID: ' + $runningAgent.ProcessId)
        return
    }
    if (-not (Test-Path -LiteralPath './config/application-local.yml')) {
        throw 'Create config/application-local.yml from the example before starting.'
    }
    $javaExecutable = Get-AgentJava
    $env:JAVA_HOME = Split-Path (Split-Path $javaExecutable -Parent) -Parent
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    $localYaml = Get-Content -LiteralPath './config/application-local.yml' -Raw
    if ($localYaml -match '(?m)^\s+api-key:\s*[\x27\x22]?(?:local-key-not-configured|fill-your-qwen-key-here)[\x27\x22]?\s*$') {
        throw 'Configure the model API key first: run config/set-model-key.ps1.'
    }
    if ($Rebuild -or -not (Test-Path -LiteralPath $script:AgentJar)) {
        & mvn -B package
        if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    }
    $yaml = Get-Content -LiteralPath './config/application-local.yml' -Raw
    $portMatch = [regex]::Match($yaml, '(?m)^\s{2}port:\s*(\d+)\s*$')
    $agentPort = 8091
    if ($portMatch.Success) { $agentPort = [int]$portMatch.Groups[1].Value }
    if (Get-NetTCPConnection -State Listen -LocalPort $agentPort -ErrorAction SilentlyContinue) {
        throw ('Port ' + $agentPort + ' is already in use. No process was stopped.')
    }
    New-Item -ItemType Directory -Path './logs' -Force | Out-Null
    $startedAgent = Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', ('"' + $script:AgentJar + '"')) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $PSScriptRoot 'logs/agent.out.log') -RedirectStandardError (Join-Path $PSScriptRoot 'logs/agent.err.log') -PassThru
    $managedAgent = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $startedAgent.Id)
    if (-not $managedAgent) { throw 'Agent exited immediately. Check logs/agent.err.log.' }
    @{ process_id = $startedAgent.Id; created_at = $managedAgent.CreationDate.ToUniversalTime().ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath $script:AgentState -Encoding UTF8
    $addressMatch = [regex]::Match($yaml, '(?m)^\s{2}address:\s*[''" ]*([\w.:-]+)[''" ]*\s*$')
    $agentAddress = '127.0.0.1'
    if ($addressMatch.Success) { $agentAddress = $addressMatch.Groups[1].Value }
    $healthAddress = $agentAddress
    if ($healthAddress -eq '0.0.0.0' -or $healthAddress -eq '::') { $healthAddress = '127.0.0.1' }
    if ($healthAddress.Contains(':')) { $healthAddress = '[' + $healthAddress + ']' }
    $healthUrl = 'http://' + $healthAddress + ':' + $agentPort + '/actuator/health'
    $ready = $false
    for ($check = 0; $check -lt 30; $check++) {
        $startedAgent.Refresh()
        if ($startedAgent.HasExited) { throw 'Agent failed to start. Check logs/agent.out.log and logs/agent.err.log.' }
        try {
            $health = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 1
            if ($health.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if ($ready) { Write-Output ('Agent started in background. PID: ' + $startedAgent.Id + '. Health: ' + $healthUrl) }
    else { Write-Output ('Agent process started but health is not ready yet. PID: ' + $startedAgent.Id + '. Check logs before submitting tasks.') }
    Write-Output 'Logs: logs/agent.out.log and logs/agent.err.log'
} finally { Pop-Location; $controlLock.Dispose() }
