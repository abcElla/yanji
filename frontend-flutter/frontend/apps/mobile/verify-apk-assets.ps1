param([string]$ApkPath=(Join-Path $PSScriptRoot 'build/app/outputs/flutter-apk/app-debug.apk'))
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[System.IO.Compression.ZipFile]::OpenRead([System.IO.Path]::GetFullPath($ApkPath))
try {
    foreach($name in @('assets/flutter_assets/FontManifest.json','assets/flutter_assets/fonts/MaterialIcons-Regular.otf','assets/flutter_assets/AssetManifest.bin')) {
        $entry=$archive.GetEntry($name)
        if(-not $entry -or $entry.Length -eq 0) { throw "前端APK缺少资源 $name，请清理构建缓存并重新打包后再安装。" }
    }
    Write-Host '前端APK图标字体与资源清单检查通过。'
} finally { $archive.Dispose() }
