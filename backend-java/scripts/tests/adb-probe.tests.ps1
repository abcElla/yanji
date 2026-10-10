param([string]$Adb='D:/bin/Android/Sdk/platform-tools/adb.exe')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../adb-probe.ps1')
$previousPreference=$ErrorActionPreference
# A nonexistent serial reproduces the cold-start probe without changing any device.
$missing=Invoke-YanjiAdbProbe -Executable $Adb -Arguments @('-s','emulator-5682','emu','avd','name')
if ($missing.ExitCode -eq 0) { throw 'A missing device must return a nonzero exit code.' }
if ($ErrorActionPreference -ne $previousPreference) { throw 'The caller error preference must be preserved.' }
$version=Invoke-YanjiAdbProbe -Executable $Adb -Arguments @('version')
if ($version.ExitCode -ne 0 -or -not ($version.Output -match 'Android Debug Bridge')) { throw 'A successful probe must retain its output.' }
Write-Output 'ADB probe regressions passed: absent device, successful output, caller preference.'
