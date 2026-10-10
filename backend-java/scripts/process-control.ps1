# Exact process identity prevents PID reuse from stopping unrelated services.
function Read-YanjiProcessRecords {
    param([string]$Path)
    $parsed = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($record in $parsed) { Write-Output $record }
}
function New-YanjiProcessRecord {
    param([int]$ProcessId, [string]$Name)
    $item = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId"
    if (-not $item -or -not $item.ExecutablePath) { throw "无法确认 $Name 的进程身份。" }
    [pscustomobject]@{name=$Name; process_id=$ProcessId; created_at=$item.CreationDate.ToUniversalTime().ToString('o'); executable=$item.ExecutablePath}
}
function Test-YanjiProcessRecord {
    param($Record)
    if (-not $Record -or -not $Record.process_id -or -not $Record.created_at -or -not $Record.executable) { return $false }
    $item = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [int]$Record.process_id)
    if (-not $item -or $item.ExecutablePath -ne $Record.executable) { return $false }
    try {
        $recordedTime = if ($Record.created_at -is [datetime]) {
            $Record.created_at.ToUniversalTime()
        } elseif ($Record.created_at -is [datetimeoffset]) {
            $Record.created_at.UtcDateTime
        } else { [datetimeoffset]::Parse([string]$Record.created_at).UtcDateTime }
        return $item.CreationDate.ToUniversalTime().Ticks -eq $recordedTime.Ticks
    } catch { return $false }
}
function Stop-YanjiRecordedProcess {
    param($Record)
    if (Test-YanjiProcessRecord $Record) {
        Stop-Process -Id ([int]$Record.process_id) -ErrorAction Stop
        Wait-Process -Id ([int]$Record.process_id) -Timeout 15 -ErrorAction SilentlyContinue
    }
}
