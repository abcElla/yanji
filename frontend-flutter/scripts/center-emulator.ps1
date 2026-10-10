param([string]$Avd='Yanji_API_36',[string]$Serial='emulator-5554')
Add-Type -AssemblyName System.Windows.Forms
if (-not ('YanjiEmulatorWindow' -as [type])) {
 Add-Type @'
using System; using System.Text; using System.Runtime.InteropServices;
public class YanjiEmulatorWindow {
 public delegate bool EnumProc(IntPtr h,IntPtr p);
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f,IntPtr p);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h,StringBuilder t,int n);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint pid);
 [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h,IntPtr after,int x,int y,int w,int height,uint flags);
 public struct RECT {public int Left,Top,Right,Bottom;}
}
'@
}
$expectedTitle='Android Emulator - '+$Avd+':'+($Serial -replace '^emulator-','')
$script:emulatorWindows=New-Object System.Collections.Generic.List[object]
[void][YanjiEmulatorWindow]::EnumWindows({param($handle,$unused)
 $title=New-Object System.Text.StringBuilder 512
 [void][YanjiEmulatorWindow]::GetWindowText($handle,$title,512)
 if([YanjiEmulatorWindow]::IsWindowVisible($handle) -and ($title.ToString() -eq $expectedTitle -or $title.ToString() -eq 'Emulator')) {
  $rect=New-Object YanjiEmulatorWindow+RECT
  [void][YanjiEmulatorWindow]::GetWindowRect($handle,[ref]$rect)
  [uint32]$owner=0
  [void][YanjiEmulatorWindow]::GetWindowThreadProcessId($handle,[ref]$owner)
  $script:emulatorWindows.Add([pscustomobject]@{handle=$handle;title=$title.ToString();owner=$owner;width=$rect.Right-$rect.Left;height=$rect.Bottom-$rect.Top})
 }
 return $true
},[IntPtr]::Zero)
$main=$script:emulatorWindows | Where-Object title -eq $expectedTitle | Select-Object -First 1
if($main -and $main.height -gt 0) {
 $area=[System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
 $height=[Math]::Min($main.height,$area.Height-48)
 $width=[int]($height*$main.width/$main.height)
 $x=$area.Left+[int](($area.Width-$width)/2)
 $y=$area.Top+[int](($area.Height-$height)/2)
 [void][YanjiEmulatorWindow]::SetWindowPos($main.handle,[IntPtr]::Zero,$x,$y,$width,$height,20)
 foreach($toolbar in ($script:emulatorWindows | Where-Object { $_.title -eq 'Emulator' -and $_.owner -eq $main.owner })) {
  [void][YanjiEmulatorWindow]::SetWindowPos($toolbar.handle,[IntPtr]::Zero,($x+$width+8),($y+16),$toolbar.width,$toolbar.height,20)
 }
 Write-Output '模拟器已居中，完整手机界面可见。'
}
