[CmdletBinding()]
param([string]$ConfigFile = "$PSScriptRoot/deployment.local.json", [string]$SqlFile = "$PSScriptRoot/data/demo.sql")
. "$PSScriptRoot/common.ps1"
$c = Get-DeploymentConfig $ConfigFile
if (!(Test-Path -LiteralPath $SqlFile)) { throw 'SQL data file does not exist.' }
if ([IO.Path]::GetFullPath($SqlFile) -eq [IO.Path]::GetFullPath("$PSScriptRoot/data/demo.sql")) {
    $conflicts = [int](Invoke-DeploymentSql $c "SELECT COUNT(*) FROM users WHERE (username='demo_mentor' AND id<>'d1000000-0000-0000-0000-000000000001') OR (username='demo_student' AND id<>'d1000000-0000-0000-0000-000000000002');")
    if ($conflicts -gt 0) { throw 'Demo usernames already belong to other accounts. Demo import stopped.' }
}
$null = Invoke-DeploymentSql $c (Get-Content -LiteralPath $SqlFile -Raw -Encoding UTF8)
Write-Host 'SQL import completed.'
