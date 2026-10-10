param([string]$DeviceId = '10AE792S3R00247')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$sdk = 'D:\Android\Sdk'
$flutter = 'D:\Android_deploy\flutter_windows_3.47.7-stable\flutter\bin\flutter.bat'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:JAVA_HOME = 'D:\Software\Android Studio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$sdk\platform-tools;$env:Path"
$env:NO_PROXY = 'localhost,127.0.0.1,::1'
foreach ($requiredPath in @($flutter, $adb, "$env:JAVA_HOME\bin\java.exe", (Join-Path $PSScriptRoot '.env'))) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw "Required file not found: $requiredPath" }
}
$config = Get-Content -LiteralPath '.env' -Raw | ConvertFrom-Json
if ($config.API_BASE_URL -ne 'http://127.0.0.1:8000') { throw 'USB debugging requires API_BASE_URL=http://127.0.0.1:8000 in .env.' }
& $adb start-server
if ($LASTEXITCODE -ne 0) { throw 'Could not start adb.' }
$devices = & $adb devices
if (-not ($devices -match ('^' + [regex]::Escape($DeviceId) + '\s+device$'))) {
    throw "Phone $DeviceId is not ready. Connect USB and accept USB debugging on the phone."
}
& $adb -s $DeviceId reverse tcp:8000 tcp:8000
if ($LASTEXITCODE -ne 0) { throw 'USB port forwarding failed.' }
& $flutter pub get
if ($LASTEXITCODE -ne 0) { throw 'Flutter dependency installation failed.' }
Write-Host 'Running on the physical phone. r = hot reload, R = hot restart, q = quit.'
& $flutter run -d $DeviceId --dart-define-from-file=.env
if ($LASTEXITCODE -ne 0) { throw 'Flutter failed. Read the error above.' }