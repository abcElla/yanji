param([string]$SourcePath = (Join-Path $PSScriptRoot 'assets/branding/yanji.jpg'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Image]::FromFile([System.IO.Path]::GetFullPath($SourcePath))
try {
    if ($source.Width -ne $source.Height) { throw 'App icon source must be square.' }
    $targets = @(
        @{ Path = 'android/app/src/main/res/mipmap-mdpi/ic_launcher.png'; Size = 48 },
        @{ Path = 'android/app/src/main/res/mipmap-hdpi/ic_launcher.png'; Size = 72 },
        @{ Path = 'android/app/src/main/res/mipmap-xhdpi/ic_launcher.png'; Size = 96 },
        @{ Path = 'android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png'; Size = 144 },
        @{ Path = 'android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png'; Size = 192 },
        @{ Path = 'web/favicon.png'; Size = 32 },
        @{ Path = 'web/icons/Icon-192.png'; Size = 192 },
        @{ Path = 'web/icons/Icon-512.png'; Size = 512 },
        @{ Path = 'web/icons/Icon-maskable-192.png'; Size = 192; Maskable = $true },
        @{ Path = 'web/icons/Icon-maskable-512.png'; Size = 512; Maskable = $true }
    )
    foreach ($target in $targets) {
        $bitmap = [System.Drawing.Bitmap]::new($target.Size, $target.Size)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $graphics.Clear([System.Drawing.Color]::FromArgb(0, 61, 230))
            $edge = if ($target.Maskable) { [int]($target.Size * 0.8) } else { $target.Size }
            $offset = [int](($target.Size - $edge) / 2)
            $graphics.DrawImage($source, $offset, $offset, $edge, $edge)
            $path = Join-Path $PSScriptRoot $target.Path
            $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
            Write-Host "$($target.Path): $($target.Size)x$($target.Size)"
        } finally {
            $graphics.Dispose()
            $bitmap.Dispose()
        }
    }
} finally { $source.Dispose() }
