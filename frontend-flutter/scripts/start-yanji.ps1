[CmdletBinding()]
param([switch]$ServicesOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'process-control.ps1')
. (Join-Path $PSScriptRoot 'adb-probe.ps1')
$configPath=Join-Path $PSScriptRoot 'runtime.local.json'
if (-not (Test-Path -LiteralPath $configPath)) { throw '请先复制 scripts/runtime.example.json 为 runtime.local.json 并填写本机路径。' }
$cfg=Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$runtimeDir=Join-Path $PSScriptRoot '.runtime'
$logDir=Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Path $runtimeDir,$logDir -Force | Out-Null
$controlLock=$null
try { $controlLock=[System.IO.File]::Open((Join-Path $runtimeDir 'control.lock'),[System.IO.FileMode]::OpenOrCreate,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::None) } catch { throw '另一个启动或关闭操作正在进行，请稍后重试。' }
$statePath=Join-Path $runtimeDir 'processes.json'
$records=@()
if (Test-Path -LiteralPath $statePath) { $records=@(Read-YanjiProcessRecords $statePath) }
function Save-State { ConvertTo-Json -InputObject @($script:records) -Depth 6 | Set-Content -LiteralPath $script:statePath -Encoding UTF8 }
function Get-PortProcess([int]$Port) {
    $listener=Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) { return Get-CimInstance Win32_Process -Filter ('ProcessId='+$listener.OwningProcess) }
    return $null
}
function Test-Http([string]$Url) {
    try { return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 } catch { return $false }
}
function Ensure-Service {
    param([string]$Name,[int]$Port,[string]$Exe,[string[]]$Arguments,[string]$Directory,[string[]]$Identity,[string]$Health='',[int]$WaitSeconds=90)
    $existing=Get-PortProcess $Port
    if ($existing) {
        $ownedChild=@($script:records | Where-Object { $_.name -eq ($Name+'工作进程') -and $_.process_id -eq $existing.ProcessId -and (Test-YanjiProcessRecord $_) })
        if ([System.IO.Path]::GetFullPath($existing.ExecutablePath) -ne [System.IO.Path]::GetFullPath($Exe) -and $ownedChild.Count -eq 0) { throw "$Name 的端口 $Port 由其他程序占用，未停止任何进程。" }
        $command=($existing.CommandLine -replace '\\','/').ToLowerInvariant()
        foreach ($needle in $Identity) {
            if (-not $command.Contains(($needle -replace '\\','/').ToLowerInvariant())) { throw "$Name 的端口 $Port 被其他服务占用，未停止任何进程。" }
        }
        if ($Health -and -not (Test-Http $Health)) { throw "$Name 进程存在但尚未就绪，请查看日志后重试。" }
        $tracked=@($script:records | Where-Object { $_.name -eq $Name -and (Test-YanjiProcessRecord $_) })
        if ($tracked.Count -gt 0 -and $tracked[0].process_id -ne $existing.ProcessId -and $existing.ParentProcessId -eq $tracked[0].process_id) {
            $recoveredChild=New-YanjiProcessRecord -ProcessId $existing.ProcessId -Name ($Name+'工作进程')
            $script:records=@($script:records | Where-Object name -ne $recoveredChild.name)+@($recoveredChild)
            Save-State
        }
        Write-Host "$Name 已在运行，复用现有服务。"
        return
    }
    $prior=@($script:records | Where-Object name -eq $Name | Where-Object { Test-YanjiProcessRecord $_ })
    if ($prior.Count -gt 0) { throw "$Name 正在启动或异常，未重复启动。请查看 scripts/logs。" }
    if (-not (Test-Path -LiteralPath $Exe)) { throw "$Name 程序不存在：$Exe" }
    $process=Start-Process -FilePath $Exe -ArgumentList $Arguments -WorkingDirectory $Directory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir "$Name.log") -RedirectStandardError (Join-Path $logDir "$Name-error.log") -PassThru
    $record=$null
    for ($i=0;$i -lt 20;$i++) {
        try { $record=New-YanjiProcessRecord -ProcessId $process.Id -Name $Name;break } catch { Start-Sleep -Milliseconds 100 }
    }
    if (-not $record) { throw "$Name 启动失败，请查看 scripts/logs/$Name-error.log。" }
    $script:records=@($script:records | Where-Object name -ne $Name)+@($record)
    Save-State
    $deadline=[datetime]::UtcNow.AddSeconds($WaitSeconds)
    while ([datetime]::UtcNow -lt $deadline) {
        if (-not (Test-YanjiProcessRecord $record)) { throw "$Name 启动后退出，请查看 scripts/logs。" }
        $listeningProcess=Get-PortProcess $Port
        if ($listeningProcess -and (-not $Health -or (Test-Http $Health))) {
            if ($listeningProcess.ProcessId -ne $process.Id) {
                if ($listeningProcess.ParentProcessId -ne $process.Id) { throw "$Name 端口出现其他进程，未接管该进程。" }
                $childRecord=New-YanjiProcessRecord -ProcessId $listeningProcess.ProcessId -Name ($Name+'工作进程')
                $script:records=@($script:records | Where-Object name -ne $childRecord.name)+@($childRecord)
                Save-State
            }
            Write-Host "$Name 启动成功。";return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name 未在规定时间内就绪，进程记录已保留，可用关闭脚本安全停止。"
}
function Quote-Argument([string]$Value) { return '"'+$Value+'"' }
function Convert-MsysPath([string]$Value,[string]$Executable) {
    $full=[System.IO.Path]::GetFullPath($Value) -replace '\\','/'
    $msysRoot=([System.IO.Path]::GetFullPath((Split-Path (Split-Path (Split-Path $Executable)))) -replace '\\','/').TrimEnd('/')+'/'
    if (-not $full.StartsWith($msysRoot,[System.StringComparison]::OrdinalIgnoreCase)) { throw 'Redis配置需要放在此MSYS运行时目录内。' }
    return '/'+$full.Substring($msysRoot.Length)
}
try {
    Ensure-Service 'MySQL' $cfg.mysql_port $cfg.mysql_exe @('--defaults-file='+$cfg.mysql_config) (Split-Path $cfg.mysql_exe) @($cfg.mysql_config) -WaitSeconds 300
    $redisConfig=Convert-MsysPath $cfg.redis_config $cfg.redis_exe
    Ensure-Service 'Redis' 6379 $cfg.redis_exe @((Quote-Argument $redisConfig)) (Split-Path $cfg.redis_config) @($redisConfig)
    $credentials=Get-Content -LiteralPath $cfg.minio_credentials -Raw -Encoding UTF8 | ConvertFrom-Json
    $previousUser=$env:MINIO_ROOT_USER;$previousPassword=$env:MINIO_ROOT_PASSWORD
    try {
        $env:MINIO_ROOT_USER=$credentials.access_key;$env:MINIO_ROOT_PASSWORD=$credentials.secret_key
        Ensure-Service 'MinIO' 9000 $cfg.minio_exe @('server',(Quote-Argument $cfg.minio_data),'--address','127.0.0.1:9000','--console-address','127.0.0.1:9001') (Split-Path $cfg.minio_exe) @($cfg.minio_data) 'http://127.0.0.1:9000/minio/health/ready'
    } finally { $env:MINIO_ROOT_USER=$previousUser;$env:MINIO_ROOT_PASSWORD=$previousPassword;$credentials=$null }
    $previousRagHome=$env:YANJI_RAG_HOME;$previousHfHome=$env:HF_HOME
    try {
        $env:YANJI_RAG_HOME=Split-Path $cfg.rag_server
        $env:HF_HOME=Join-Path $env:YANJI_RAG_HOME 'hf-cache'
        Ensure-Service 'RAG' 8092 $cfg.rag_python @((Quote-Argument $cfg.rag_server)) (Split-Path $cfg.rag_server) @($cfg.rag_server) 'http://127.0.0.1:8092/health' 240
    } finally { $env:YANJI_RAG_HOME=$previousRagHome;$env:HF_HOME=$previousHfHome }
    $backendJar=Join-Path $cfg.backend_root 'target/backend-java-1.0.0.jar'
    $agentJar=Join-Path $cfg.agent_root 'target/yanji-agent-0.1.0.jar'
    foreach ($jar in @($backendJar,$agentJar)) { if (-not (Test-Path -LiteralPath $jar)) { throw "缺少编译后的程序：$jar，请先构建。" } }
    Ensure-Service '后端' 8000 $cfg.java @('-Xms128m','-Xmx512m','-jar',(Quote-Argument $backendJar),'--spring.profiles.active=mysql,local') $cfg.backend_root @($backendJar) ($cfg.backend_url+'/health/live')
    Ensure-Service 'Agent' 8091 $cfg.java @('-Xms96m','-Xmx384m','-jar',(Quote-Argument $agentJar)) $cfg.agent_root @($agentJar) ($cfg.agent_url+'/actuator/health')
    $readyDeadline=[datetime]::UtcNow.AddSeconds(90)
    while (-not (Test-Http ($cfg.backend_url+'/health/ready'))) {
        if ([datetime]::UtcNow -ge $readyDeadline) { throw '各端进程已启动，但后端后台队列尚未就绪，请查看 scripts/logs/后端.log。' }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ServicesOnly) {
        $avdProbe=Invoke-YanjiAdbProbe -Executable $cfg.adb -Arguments @('-s',$cfg.serial,'emu','avd','name')
        $avdName=$avdProbe.Output
        if ($avdProbe.ExitCode -ne 0) {
            if ($cfg.serial -notmatch '^emulator-(\d+)$') { throw '模拟器设备编号无效。' }
            $emulatorPort=[int]$Matches[1]
            if ($emulatorPort -lt 5554 -or $emulatorPort -gt 5682 -or $emulatorPort % 2 -ne 0) { throw '模拟器需要5554到5682之间的偶数端口。' }
            if (Get-PortProcess $emulatorPort) { throw '模拟器端口已被其他进程占用，未启动新设备。' }
            $previousAvdHome=$env:ANDROID_AVD_HOME
            try {
                if ($cfg.avd_home) { $env:ANDROID_AVD_HOME=$cfg.avd_home }
                $emulatorProcess=Start-Process -FilePath $cfg.emulator -ArgumentList @('-avd',$cfg.avd,'-port',$emulatorPort,'-memory',$cfg.emulator_memory_mb,'-no-snapshot-load','-no-snapshot-save') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDir 'emulator.log') -RedirectStandardError (Join-Path $logDir 'emulator-error.log')
            } finally { $env:ANDROID_AVD_HOME=$previousAvdHome }
            $launcherRecord=New-YanjiProcessRecord $emulatorProcess.Id '模拟器启动器'
            $script:records=@($script:records | Where-Object name -ne '模拟器启动器')+@($launcherRecord)
            Save-State
            $bootDeadline=[datetime]::UtcNow.AddSeconds(240)
            do {
                $qemu=Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'qemu-system*' -and $_.ParentProcessId -eq $emulatorProcess.Id -and $_.CreationDate.ToUniversalTime() -ge $emulatorProcess.StartTime.ToUniversalTime().AddSeconds(-1) } | Select-Object -First 1
                if ($qemu) { $script:records=@($script:records | Where-Object name -ne '模拟器')+@(New-YanjiProcessRecord $qemu.ProcessId '模拟器');Save-State }
                Start-Sleep -Seconds 2
                $bootProbe=Invoke-YanjiAdbProbe -Executable $cfg.adb -Arguments @('-s',$cfg.serial,'shell','getprop','sys.boot_completed')
                if ($bootProbe.ExitCode -eq 0 -and $bootProbe.Output -match '^1') { break }
                if ([datetime]::UtcNow -gt $bootDeadline) { throw '模拟器启动超时，请查看 scripts/logs/emulator-error.log。' }
            } while ($true)
        } elseif (-not (@($avdName) -contains $cfg.avd)) { throw '目标设备不是研迹模拟器，未操作其他设备。' }
        $verifiedAvd=& $cfg.adb -s $cfg.serial emu avd name 2>$null
        if ($LASTEXITCODE -ne 0 -or -not (@($verifiedAvd) -contains $cfg.avd)) { throw '安装前设备核对失败，未操作该设备。' }
        $apk=Join-Path $cfg.mobile_root 'build/app/outputs/flutter-apk/app-debug.apk'
        if (-not (Test-Path -LiteralPath $apk)) { throw '缺少前端APK，请先 flutter build apk --debug。' }
        & (Join-Path $cfg.mobile_root 'verify-apk-assets.ps1') -ApkPath $apk
        & $cfg.adb -s $cfg.serial install -r $apk
        if ($LASTEXITCODE -ne 0) { throw '前端安装失败。' }
        & $cfg.adb -s $cfg.serial shell am start -n ($cfg.package+'/.MainActivity')
        if ($LASTEXITCODE -ne 0) { throw '前端打开失败。' }
        & (Join-Path $PSScriptRoot 'center-emulator.ps1') -Avd $cfg.avd -Serial $cfg.serial
        $script:records=@($script:records | Where-Object name -ne '前端')+@([pscustomobject]@{name='前端';serial=$cfg.serial;avd=$cfg.avd;package=$cfg.package})
        Save-State
    }
    Write-Host '研迹已启动。可以使用学生和导师账号测试；关闭时双击“关闭研迹.cmd”。'
} finally { if ($controlLock) { $controlLock.Dispose() } }
