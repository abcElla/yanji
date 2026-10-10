[CmdletBinding()]
param([string]$ConfigFile = '', [switch]$DemoLogin)
. "$PSScriptRoot/common.ps1"
$c = Get-DeploymentConfig $ConfigFile
$base = "http://127.0.0.1:$($c.BackendPort)"
$checks = @("$base/health/live","$base/health/ready","http://127.0.0.1:$($c.AgentPort)/actuator/health","http://127.0.0.1:$($c.MinioPort)/minio/health/live")
foreach ($url in $checks) {
    $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 10
    if ($response.StatusCode -ne 200) { throw "Health check failed: $url" }
    Write-Host "PASS $url"
}
if ($DemoLogin) {
    foreach ($name in @('demo_mentor','demo_student')) {
        $body = @{username=$name; password='YanjiDemo2026!'; installation_id=[guid]::NewGuid().ToString(); device_label='deployment-check'} | ConvertTo-Json
        $login = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" -ContentType 'application/json' -Body $body
        $headers = @{Authorization="Bearer $($login.data.access_token)"}
        $me = Invoke-RestMethod -Uri "$base/api/v1/me" -Headers $headers
        if ($me.data.user.id -ne $login.data.user.id) { throw 'Account verification failed.' }
        $null = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/logout" -Headers $headers
        Write-Host "PASS login and profile: $name"
    }
}
Write-Host 'Service checks passed. Verify one AI request in the app to check model access and quota.'
