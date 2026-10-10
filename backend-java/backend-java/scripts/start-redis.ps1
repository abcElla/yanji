param([string]$RedisHome = 'D:\Software\Redis', [string]$RedisRuntimeHome = 'D:\work\中兴杯\dependence\redis', [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
function Test-YanjiRedis {
    $probe = New-Object System.Net.Sockets.TcpClient
    try {
        $probe.SendTimeout = 1000; $probe.ReceiveTimeout = 1000
        if (-not $probe.ConnectAsync('127.0.0.1', 6379).Wait(1000)) { return $false }
        $stream = $probe.GetStream()
        $bytes = [Text.Encoding]::ASCII.GetBytes(('*1' + "`r`n" + '$4' + "`r`nPING`r`n"))
        $stream.Write($bytes, 0, $bytes.Length)
        $reply = New-Object byte[] 128
        $length = $stream.Read($reply, 0, $reply.Length)
        return [Text.Encoding]::ASCII.GetString($reply, 0, $length).StartsWith('+PONG')
    } catch { return $false } finally { $probe.Dispose() }
}
if (Test-YanjiRedis) { Write-Host 'Redis: ready at 127.0.0.1:6379.'; return }
if ($CheckOnly) { throw 'Redis is not ready at 127.0.0.1:6379.' }
$exe = Join-Path $RedisHome 'redis-server.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw 'Existing Redis executable is missing. Check RedisHome.' }
$probe = New-Object System.Net.Sockets.TcpClient
try {
    $connected = $false
    try { $connected = $probe.ConnectAsync('127.0.0.1', 6379).Wait(1000) -and $probe.Connected } catch {}
    if ($connected) { throw 'Port 6379 is occupied by an unverified service; check it before starting Redis.' }
} finally { $probe.Dispose() }
$binaryHome = $RedisRuntimeHome
New-Item -ItemType Directory -Path (Join-Path $binaryHome 'data') -Force | Out-Null
$config = Join-Path $binaryHome 'yanji-cache.conf'
@"
bind 127.0.0.1
protected-mode yes
port 6379
save ""
appendonly no
maxmemory 64mb
maxmemory-policy allkeys-lru
dir "./data"
"@ | Set-Content -LiteralPath $config -Encoding ascii
$process = Start-Process -FilePath $exe -ArgumentList 'yanji-cache.conf' -WorkingDirectory $binaryHome -WindowStyle Hidden -RedirectStandardOutput (Join-Path $RedisRuntimeHome 'redis.log') -RedirectStandardError (Join-Path $RedisRuntimeHome 'redis-error.log') -PassThru
Set-Content -LiteralPath (Join-Path $RedisRuntimeHome 'redis.pid') -Value $process.Id -Encoding ascii
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if (Test-YanjiRedis) { Write-Host 'Redis: ready at 127.0.0.1:6379 (ephemeral cache, 64MB).'; return }
    if ($process.HasExited) { throw "Redis exited; inspect $RedisRuntimeHome\redis-error.log." }
    Start-Sleep -Milliseconds 500
}
throw "Redis startup timed out; inspect $RedisRuntimeHome logs."
