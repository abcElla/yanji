[CmdletBinding()]
param([string]$ConfigFile = '')
. "$PSScriptRoot/common.ps1"
$c = Get-DeploymentConfig $ConfigFile
$null = Use-DeploymentJava $c
foreach ($project in @($BackendProject,$AgentProject)) {
    if (!(Test-Path -LiteralPath (Join-Path $project 'pom.xml'))) { throw "Missing source project: $project. Clone the complete yanji repository as documented." }
    Push-Location $project
    try { & mvn.cmd -B -DskipTests package; if ($LASTEXITCODE -ne 0) { throw "Maven build failed: $project" } }
    finally { Pop-Location }
}
