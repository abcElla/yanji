[CmdletBinding()]
param([string]$ConfigFile = "$PSScriptRoot/deployment.local.json", [string]$AdminUser = 'root', [switch]$WithDemo)
. "$PSScriptRoot/common.ps1"
$c = Get-DeploymentConfig $ConfigFile
$adminPassword = Read-PrivateValue 'MySQL administrator password (hidden)'
$passwordSql = ConvertTo-SqlLiteral $c.DatabasePassword
$userSql = ConvertTo-SqlLiteral $c.DatabaseUser
$sql = "CREATE DATABASE IF NOT EXISTS ``$($c.Database)`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
foreach ($hostName in @('localhost','127.0.0.1')) {
    $hostSql = ConvertTo-SqlLiteral $hostName
    $sql += "`nCREATE USER IF NOT EXISTS $userSql@$hostSql IDENTIFIED BY $passwordSql;"
    $sql += "`nGRANT ALL PRIVILEGES ON ``$($c.Database)``.* TO $userSql@$hostSql;"
}
$null = Invoke-DeploymentSql $c $sql -User $AdminUser -Password $adminPassword -WithoutDatabase
$count = [int](Invoke-DeploymentSql $c 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();')
if ($count -eq 0) {
    $schema = Join-Path $BackendProject 'sql/000_current_schema_mysql.sql'
    $null = Invoke-DeploymentSql $c (Get-Content -LiteralPath $schema -Raw -Encoding UTF8)
    Write-Host 'Created current database schema.'
} else { Write-Host 'Database contains tables; current schema initialization was skipped.' }
& "$PSScriptRoot/migrate-database.ps1" -ConfigFile $ConfigFile
if ($WithDemo) { & "$PSScriptRoot/import-data.ps1" -ConfigFile $ConfigFile }
Write-Host 'Database ready.'
