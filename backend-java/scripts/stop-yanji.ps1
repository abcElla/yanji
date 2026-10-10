$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'process-control.ps1')
. (Join-Path $PSScriptRoot 'adb-probe.ps1')
$runtimeDir=Join-Path $PSScriptRoot '.runtime'
$statePath=Join-Path $runtimeDir 'processes.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Host '没有本脚本管理的服务需要关闭。';return }
$controlLock=$null
try { $controlLock=[System.IO.File]::Open((Join-Path $runtimeDir 'control.lock'),[System.IO.FileMode]::OpenOrCreate,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::None) } catch { throw '另一个启动或关闭操作正在进行，请稍后重试。' }
try {
    $cfg=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime.local.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $records=@(Read-YanjiProcessRecords $statePath)
    $stopOrder=@{'前端'=0;'模拟器'=1;'模拟器启动器'=2;'Agent'=3;'后端'=4;'RAG'=5;'MinIO'=6;'Redis'=7;'MySQL工作进程'=8;'MySQL'=9}
    $records=@($records | Sort-Object {
        if($stopOrder.ContainsKey($_.name)) { $stopOrder[$_.name] }
        elseif($_.name.EndsWith('工作进程') -and $stopOrder.ContainsKey($_.name.Replace('工作进程',''))) { $stopOrder[$_.name.Replace('工作进程','')]-0.1 }
        else { 10 }
    })
    foreach ($record in $records) {
        if ($record.name -eq '前端') {
            $probe=Invoke-YanjiAdbProbe -Executable $cfg.adb -Arguments @('-s',$record.serial,'emu','avd','name')
            if ($probe.ExitCode -eq 0 -and @($probe.Output) -contains $record.avd) { & $cfg.adb -s $record.serial shell am force-stop $record.package }
        } else {
            if (Test-YanjiProcessRecord $record) { Stop-YanjiRecordedProcess $record;Write-Host ($record.name+' 已关闭。') }
        }
    }
    Set-Content -LiteralPath $statePath -Value '[]' -Encoding UTF8
    Write-Host '关闭完成。复用的原有服务和所有数据均保留。'
} finally { if ($controlLock) { $controlLock.Dispose() } }
