[CmdletBinding()]
param([string]$ConfigFile = '')
. "$PSScriptRoot/common.ps1"
$c = Get-DeploymentConfig $ConfigFile
$null = Invoke-DeploymentSql $c 'CREATE TABLE IF NOT EXISTS yanji_schema_migrations (filename VARCHAR(160) PRIMARY KEY, sha256 CHAR(64) NOT NULL, applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);'
$names = @('007_theses.sql','008_research_followups.sql','009_research_experiences.sql','010_allow_multiple_weekly_reports.sql')
foreach ($name in $names) {
    $path = Join-Path $BackendProject "sql/$name"
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $nameSql = ConvertTo-SqlLiteral $name
    $existing = Invoke-DeploymentSql $c "SELECT sha256 FROM yanji_schema_migrations WHERE filename=$nameSql;"
    if ($existing) {
        if ([string]$existing -ne $hash) { throw "Applied migration changed: $name. Restore the recorded version and create a new migration." }
        Write-Host "Already applied: $name"
        continue
    }
    $null = Invoke-DeploymentSql $c (Get-Content -LiteralPath $path -Raw -Encoding UTF8)
    $null = Invoke-DeploymentSql $c "INSERT INTO yanji_schema_migrations(filename,sha256) VALUES ($nameSql,'$hash');"
    Write-Host "Applied: $name"
}
