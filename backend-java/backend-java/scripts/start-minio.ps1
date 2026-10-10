param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$dependencyRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\dependence'))
$python = Join-Path $dependencyRoot 'rag\.venv\Scripts\python.exe'
$manager = Join-Path $dependencyRoot 'minio\manage-minio.py'
if ($CheckOnly) { & $python $manager --check }
else { & $python $manager }
if ($LASTEXITCODE -ne 0) { throw 'MinIO dependency failed. Read the diagnostic above.' }
