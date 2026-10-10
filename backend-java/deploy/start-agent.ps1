[CmdletBinding()]
param([string]$ConfigFile = '')
. "$PSScriptRoot/common.ps1"
$c = Get-DeploymentConfig $ConfigFile
if (!$c.ModelApiKey -or $c.ModelApiKey -eq 'fill-in-locally') { throw 'Set ModelApiKey in private deployment.local.json before starting the agent.' }
$java = Use-DeploymentJava $c
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = [string]$c.AgentPort
$env:SPRING_AI_OPENAI_API_KEY = $c.ModelApiKey
$env:SPRING_AI_OPENAI_BASE_URL = $c.ModelBaseUrl
$env:SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL = $c.ModelName
$env:AGENT_RECEIVER_KEY = $c.AgentReceiverKey
$env:AGENT_BACKEND_BASE_URL = "http://127.0.0.1:$($c.BackendPort)"
$env:AGENT_BACKEND_SERVICE_KEY = $c.InternalServiceKey
$jar = Join-Path $AgentProject 'target/yanji-agent-0.1.0.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Run deploy/build.ps1 first.' }
Push-Location $AgentProject
try { & $java -jar $jar; if ($LASTEXITCODE -ne 0) { throw 'Agent stopped with an error.' } }
finally { Pop-Location }
