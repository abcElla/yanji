[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ApiBaseUrl, [switch]$Install, [string]$DeviceId = '')
. "$PSScriptRoot/common.ps1"
$uri = $null
if (![Uri]::TryCreate($ApiBaseUrl,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -notin @('http','https')) { throw 'ApiBaseUrl must be an absolute HTTP(S) URL.' }
if (!(Test-Path -LiteralPath (Join-Path $MobileProject 'pubspec.yaml'))) { throw 'Clone the complete yanji repository; frontend-flutter must be beside backend-java.' }
Push-Location $MobileProject
try {
    & flutter.bat pub get
    if ($LASTEXITCODE -ne 0) { throw 'flutter pub get failed.' }
    & flutter.bat build apk --debug "--dart-define=API_BASE_URL=$($ApiBaseUrl.TrimEnd('/'))"
    if ($LASTEXITCODE -ne 0) { throw 'APK build failed.' }
    $apk = Join-Path $MobileProject 'build/app/outputs/flutter-apk/app-debug.apk'
    Write-Host "APK: $apk"
    if ($Install) {
        $adb = Get-Command adb.exe -ErrorAction SilentlyContinue
        if (!$adb) { throw 'Add Android SDK platform-tools to PATH before using -Install.' }
        $devices = @(& $adb.Source devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
        if (!$DeviceId) {
            if ($devices.Count -ne 1) { throw 'Connect one authorized Android device or specify -DeviceId.' }
            $DeviceId = $devices[0]
        }
        if ($DeviceId -notin $devices) { throw 'The selected Android device is not connected/authorized.' }
        & $adb.Source -s $DeviceId install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
    }
} finally { Pop-Location }
