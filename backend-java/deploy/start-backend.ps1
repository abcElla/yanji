[CmdletBinding()]
param([string]$ConfigFile = '')
. "$PSScriptRoot/common.ps1"
$c = Get-DeploymentConfig $ConfigFile
$java = Use-DeploymentJava $c
Set-BackendEnvironment $c
$jar = Join-Path $BackendProject 'target/backend-java-1.0.0.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Run deploy/build.ps1 first.' }
Push-Location $BackendProject
try { & $java -jar $jar --spring.profiles.active=mysql; if ($LASTEXITCODE -ne 0) { throw 'Backend stopped with an error.' } }
finally { Pop-Location }
