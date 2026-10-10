$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../process-control.ps1')
$testDir = Join-Path ([System.IO.Path]::GetTempPath()) ('yanji-process-test-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testDir | Out-Null
$worker = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList '-NoProfile -Command "Start-Sleep -Seconds 45"' -WindowStyle Hidden -PassThru
try {
    $record = New-YanjiProcessRecord -ProcessId $worker.Id -Name 'test-worker'
    $recordsPath = Join-Path $testDir 'processes.json'
    ConvertTo-Json -InputObject @($record,$record) | Set-Content -LiteralPath $recordsPath -Encoding UTF8
    $loaded = @(Read-YanjiProcessRecords $recordsPath)
    if ($loaded.Count -ne 2 -or $loaded[0] -is [array] -or -not (Test-YanjiProcessRecord $loaded[0])) { throw 'Multiple JSON records must be flat in Windows PowerShell and PowerShell 7.' }
    if (-not (Test-YanjiProcessRecord $record)) { throw 'Fresh managed process must match.' }
    $serializedTime = $record.PSObject.Copy()
    $serializedTime.created_at = ([datetime]$record.created_at).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.ffffffZ')
    if (-not (Test-YanjiProcessRecord $serializedTime)) { throw 'JSON timestamp precision must not change process identity.' }
    $jsonRecord = $record | ConvertTo-Json | ConvertFrom-Json
    if (-not (Test-YanjiProcessRecord $jsonRecord)) { throw 'Deserialized process identity must match.' }
    $wrongTime = $record.PSObject.Copy()
    $wrongTime.created_at = '2000-01-01T00:00:00.0000000Z'
    if (Test-YanjiProcessRecord $wrongTime) { throw 'Reused PID must be rejected.' }
    $wrongPath = $record.PSObject.Copy()
    $wrongPath.executable = 'C:\different-java.exe'
    if (Test-YanjiProcessRecord $wrongPath) { throw 'Different executable must be rejected.' }
    Stop-YanjiRecordedProcess $wrongTime
    if (-not (Get-Process -Id $worker.Id -ErrorAction SilentlyContinue)) { throw 'Mismatched process was stopped.' }
    if (-not (Test-YanjiProcessRecord $record)) { throw ('Original identity changed: '+($record | ConvertTo-Json -Compress)) }
    Stop-YanjiRecordedProcess $record
    if (Test-YanjiProcessRecord $record) { throw 'Managed process was not stopped.' }
    Stop-YanjiRecordedProcess $record
    Write-Output 'PASS: identity match, PID reuse rejection, executable rejection, safe stop, repeated stop.'
} finally {
    if (Get-Process -Id $worker.Id -ErrorAction SilentlyContinue) { Stop-Process -Id $worker.Id }
}
