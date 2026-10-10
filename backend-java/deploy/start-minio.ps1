[CmdletBinding()]
param([string]$ConfigFile = '')
. "$PSScriptRoot/common.ps1"
$c = Get-DeploymentConfig $ConfigFile
$env:MINIO_ROOT_USER = $c.MinioUser
$env:MINIO_ROOT_PASSWORD = $c.MinioPassword
$data = Join-Path $DeployDir 'runtime/minio-data'
$null = New-Item -ItemType Directory -Path $data -Force
& $c.MinioExe server $data --address "127.0.0.1:$($c.MinioPort)" --console-address "127.0.0.1:$($c.MinioConsolePort)"
if ($LASTEXITCODE -ne 0) { throw 'MinIO stopped with an error.' }
