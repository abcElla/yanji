param(
    [Parameter(Mandatory = $true)][Guid]$RunId,
    [string]$AgentUrl = '',
    [Guid]$EventId = [Guid]::NewGuid()
)
$ErrorActionPreference = 'Stop'
$configFile = Join-Path $PSScriptRoot 'config/application-local.yml'
$configText = Get-Content -LiteralPath $configFile -Raw
if ([string]::IsNullOrWhiteSpace($AgentUrl)) {
    $addressMatch = [regex]::Match($configText, '(?m)^\s{2}address:\s*[''" ]*([\w.:-]+)[''" ]*\s*$')
    $portMatch = [regex]::Match($configText, '(?m)^\s{2}port:\s*(\d+)\s*$')
    $address = '127.0.0.1'
    $port = 8091
    if ($addressMatch.Success) { $address = $addressMatch.Groups[1].Value }
    if ($portMatch.Success) { $port = [int]$portMatch.Groups[1].Value }
    if ($address -eq '0.0.0.0' -or $address -eq '::') { $address = '127.0.0.1' }
    if ($address.Contains(':')) { $address = '[' + $address + ']' }
    $AgentUrl = 'http://' + $address + ':' + $port
}
$keyMatch = [regex]::Match($configText, '(?m)^\s*receiver-key:\s*["'']?([A-Za-z0-9_-]+)["'']?\s*$')
if (-not $keyMatch.Success) { throw 'Receiver key was not found in the local YAML configuration.' }
$payload = @{ schema_version = 1; run_id = $RunId.ToString(); event_id = $EventId.ToString() } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri ($AgentUrl.TrimEnd('/') + '/internal/v1/tasks') -ContentType 'application/json; charset=utf-8' -Headers @{ 'X-Agent-Key' = $keyMatch.Groups[1].Value } -Body $payload
