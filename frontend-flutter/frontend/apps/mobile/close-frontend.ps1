param([string]$DeviceId = '10AE792S3R00247')
$ErrorActionPreference = 'Stop'
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'adb was not found.' }
& $adb -s $DeviceId shell am force-stop com.example.yanji_mobile
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the app on the selected phone.' }
& $adb -s $DeviceId reverse --remove tcp:8000
Write-Host 'App stopped on the phone. USB forwarding removed.'