$ErrorActionPreference = 'Stop'
$configPath = Join-Path $PSScriptRoot 'application-local.yml'
if (-not (Test-Path -LiteralPath $configPath)) { throw 'application-local.yml is missing.' }
$secureKey = Read-Host 'Enter the Qwen API key (input hidden)' -AsSecureString
$keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
try {
    $modelKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
    if ([string]::IsNullOrWhiteSpace($modelKey) -or $modelKey -match '[\r\n]') { throw 'API key must be a non-empty single line.' }
    $yaml = Get-Content -LiteralPath $configPath -Raw
    $pattern = '(?m)^(\s+api-key:)\s*[^\r\n]*'
    if (-not [regex]::IsMatch($yaml, $pattern)) { throw 'api-key field was not found.' }
    $quotedKey = "'" + $modelKey.Replace("'", "''") + "'"
    $updated = [regex]::Replace($yaml, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $match.Groups[1].Value + ' ' + $quotedKey })
    [IO.File]::WriteAllText($configPath, $updated, (New-Object Text.UTF8Encoding($false)))
    Write-Host 'Model key saved to the ignored local configuration. Restart Agent to apply.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
    $modelKey = $null
    $quotedKey = $null
    $updated = $null
    $secureKey.Dispose()
}