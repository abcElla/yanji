function Invoke-YanjiAdbProbe {
    param([string]$Executable,[string[]]$Arguments)
    if (-not (Test-Path -LiteralPath $Executable)) { throw 'ADB executable does not exist.' }
    $previousPreference=$ErrorActionPreference
    try {
        # Windows PowerShell promotes native stderr under Stop before exit-code handling.
        # Missing/offline devices are expected during boot; only these probes tolerate it.
        $ErrorActionPreference='Continue'
        $output=& $Executable @Arguments 2>$null
        [pscustomobject]@{Output=@($output);ExitCode=$LASTEXITCODE}
    } finally { $ErrorActionPreference=$previousPreference }
}
