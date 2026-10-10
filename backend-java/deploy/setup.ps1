[CmdletBinding()]
param()
. "$PSScriptRoot/common.ps1"
$file = Join-Path $DeployDir 'deployment.local.json'
if (Test-Path -LiteralPath $file) { throw 'Configuration already exists. Edit deployment.local.json locally instead.' }
$c = Get-Content (Join-Path $DeployDir 'deployment.example.json') -Raw | ConvertFrom-Json
$c.JavaHome = Read-Host 'JDK 21+ home (Enter to use JAVA_HOME/PATH)'
if (!$c.JavaHome -and $env:JAVA_HOME) { $c.JavaHome = $env:JAVA_HOME }
$mysql = Read-Host 'mysql.exe full path (Enter to use PATH)'
if ($mysql) { $c.MysqlExe = $mysql }
$minio = Read-Host 'minio.exe full path (Enter to use USERPROFILE/go/bin/minio.exe)'
if ($minio) { $c.MinioExe = $minio } else { $c.MinioExe = Join-Path $env:USERPROFILE 'go/bin/minio.exe' }
$c.DatabasePassword = Read-PrivateValue 'Set application database password (hidden)'
if (!$c.DatabasePassword) { throw 'Database password cannot be empty.' }
$c.ModelApiKey = Read-PrivateValue 'DashScope API key (hidden; Enter to configure AI later)'
$c.MinioPassword = New-DeploymentKey
$c.InternalServiceKey = New-DeploymentKey
$c.AgentReceiverKey = New-DeploymentKey
$null = Use-DeploymentJava $c
$c | ConvertTo-Json | Set-Content -LiteralPath $file -Encoding UTF8
Write-Host 'Saved deployment.local.json. This private file is ignored by Git; keep it on your computer.'
