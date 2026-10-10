$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
if (-not ('Yanji.EmulatorWindow' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
namespace Yanji {
    public static class EmulatorWindow {
        public delegate bool WindowCallback(IntPtr window, IntPtr state);
        [DllImport("user32.dll")] public static extern bool EnumWindows(WindowCallback callback, IntPtr state);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr window, StringBuilder title, int size);
        [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr window, int command);
        [DllImport("user32.dll", SetLastError = true)] public static extern bool SetWindowPos(IntPtr window, IntPtr after, int x, int y, int width, int height, uint flags);
        [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
        [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window, out Rect rect);
        public static IntPtr Find() {
            IntPtr result = IntPtr.Zero;
            EnumWindows((window, state) => {
                var title = new StringBuilder(512);
                GetWindowText(window, title, title.Capacity);
                if (title.ToString() == "Android Emulator - Yanji_API_36:5554" ||
                    title.ToString().StartsWith("Android Emulator - Yanji_API_36:")) {
                    result = window;
                    return false;
                }
                return true;
            }, IntPtr.Zero);
            return result;
        }
    }
}
'@
}
$window = [IntPtr]::Zero
for ($attempt = 0; $attempt -lt 15; $attempt++) {
    $window = [Yanji.EmulatorWindow]::Find()
    if ($window -ne [IntPtr]::Zero) { break }
    Start-Sleep -Seconds 1
}
if ($window -eq [IntPtr]::Zero) { throw 'Yanji emulator window was not found.' }
$area = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$height = [Math]::Min(860, $area.Height - 80)
$width = [Math]::Min(420, [int](($height - 30) * 1080 / 2424 + 16))
$left = $area.Left + [int](($area.Width - $width) / 2)
$top = $area.Top + [int](($area.Height - $height) / 2)
[Yanji.EmulatorWindow]::ShowWindow($window, 9) | Out-Null
# Remove topmost status and place the full window inside the primary work area.
for ($attempt = 0; $attempt -lt 3; $attempt++) {
    if (-not [Yanji.EmulatorWindow]::SetWindowPos($window, [IntPtr](-2), $left, $top, $width, $height, 0x0040)) {
        throw 'Unable to reposition the emulator window.'
    }
    Start-Sleep -Milliseconds 500
}
$rect = New-Object Yanji.EmulatorWindow+Rect
[Yanji.EmulatorWindow]::GetWindowRect($window, [ref]$rect) | Out-Null
if ($rect.Top -lt $area.Top -or $rect.Left -lt $area.Left -or $rect.Bottom -gt $area.Bottom -or $rect.Right -gt $area.Right) {
    throw 'Emulator window is still outside the desktop work area.'
}
Write-Host "Emulator positioned inside desktop: $($rect.Left),$($rect.Top) - $($rect.Right),$($rect.Bottom)"
