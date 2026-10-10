param([string]$RagHome = 'D:\work\中兴杯\dependence\rag', [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
function Test-YanjiEmbedding {
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:8092/health' -TimeoutSec 2
        return $health.status -eq 'ready' -and $health.model -eq 'BAAI/bge-small-zh-v1.5'
    } catch { return $false }
}
if (Test-YanjiEmbedding) { Write-Host 'Embedding: ready at 127.0.0.1:8092 (BGE Chinese, 512 dimensions).'; return }
if ($CheckOnly) { throw 'Embedding service is not ready at 127.0.0.1:8092.' }
$python = Join-Path $RagHome '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'Embedding runtime is missing. Run scripts/install-rag-dependencies.ps1 first.' }
$pidFile = Join-Path $RagHome 'embedding.pid'
if (Test-Path -LiteralPath $pidFile) {
    $existing = Get-Process -Id ([int](Get-Content -LiteralPath $pidFile)) -ErrorAction SilentlyContinue
    if ($existing -and $existing.Path -eq $python) { throw 'Embedding process is still initializing or unhealthy; inspect embedding-error.log instead of launching a duplicate.' }
}
$probe = New-Object System.Net.Sockets.TcpClient
try {
    $connected = $false
    try { $connected = $probe.ConnectAsync('127.0.0.1', 8092).Wait(1000) -and $probe.Connected } catch {}
    if ($connected) { throw 'Port 8092 is occupied by an unverified service.' }
} finally { $probe.Dispose() }
$source = Join-Path (Join-Path $PSScriptRoot '..') 'tools\embedding_server.py'
$server = Join-Path $RagHome 'embedding_server.py'
Copy-Item -LiteralPath $source -Destination $server
$env:YANJI_RAG_HOME = $RagHome
$env:HF_HOME = Join-Path $RagHome 'hf-cache'
$env:HF_HUB_DISABLE_TELEMETRY = '1'
$env:NO_PROXY = 'localhost,127.0.0.1,::1'
$process = Start-Process -FilePath $python -ArgumentList ('"' + $server + '"') -WorkingDirectory $RagHome -WindowStyle Hidden -RedirectStandardOutput (Join-Path $RagHome 'embedding.log') -RedirectStandardError (Join-Path $RagHome 'embedding-error.log') -PassThru
Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ascii
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    if (Test-YanjiEmbedding) { Write-Host 'Embedding: ready at 127.0.0.1:8092.'; return }
    if ($process.HasExited) { throw 'Embedding service exited; inspect embedding-error.log.' }
    Start-Sleep -Seconds 1
}
throw 'Embedding initialization is still pending. Inspect logs and retry -CheckOnly after the model download completes.'
