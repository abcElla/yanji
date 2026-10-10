param(
    [string]$RagHome = 'D:\work\中兴杯\dependence\rag',
    [string]$PythonPath = 'D:\work\中兴杯\dependence\rag\python\cpython-3.11-windows-x86_64-none\python.exe'
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $RagHome -Force | Out-Null
$python = Join-Path $RagHome '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) {
    if (-not (Test-Path -LiteralPath $PythonPath)) { throw 'Set PythonPath to an existing Python 3.11+ executable.' }
    & $PythonPath -m venv (Join-Path $RagHome '.venv')
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated Embedding runtime.' }
}
$packages = @('fastembed==0.7.4', 'fastapi>=0.115,<1', 'uvicorn>=0.30,<1', 'pymysql[rsa]>=1.1,<2', 'pyyaml>=6,<7')
$uv = Get-Command uv -ErrorAction SilentlyContinue
if ($uv) { & $uv.Source pip install --python $python --cache-dir (Join-Path $RagHome 'pip-cache') @packages }
else { & $python -m pip install --cache-dir (Join-Path $RagHome 'pip-cache') @packages }
if ($LASTEXITCODE -ne 0) { throw 'Embedding dependencies installation failed.' }
Copy-Item -LiteralPath (Join-Path (Join-Path $PSScriptRoot '..') 'tools\embedding_server.py') -Destination (Join-Path $RagHome 'embedding_server.py')
Write-Host "RAG dependencies ready at $RagHome. Redis and MySQL use the existing local installations."