[CmdletBinding()]
param([string]$MySqlServiceName = 'MySQL', [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$env:NO_PROXY = 'localhost,127.0.0.1,::1'
$mysql = Get-Service -Name $MySqlServiceName -ErrorAction Stop
if ($mysql.Status -ne 'Running') {
    if ($CheckOnly) { throw "MySQL service $MySqlServiceName is not running." }
    Start-Service -Name $MySqlServiceName
    $mysql.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Running, [timespan]::FromSeconds(30))
}
Write-Host "MySQL service $MySqlServiceName is running (database authentication is checked separately)."
& (Join-Path $PSScriptRoot 'start-redis.ps1') -CheckOnly:$CheckOnly
& (Join-Path $PSScriptRoot 'start-rag.ps1') -CheckOnly:$CheckOnly
& (Join-Path $PSScriptRoot 'start-minio.ps1') -CheckOnly:$CheckOnly
Write-Host 'Dependencies ready: existing MySQL, existing Redis, local BGE Embedding, and project MinIO.'
