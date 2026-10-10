$ErrorActionPreference = 'Stop'
$script:AgentRoot = $PSScriptRoot
$script:AgentJar = Join-Path $script:AgentRoot 'target/yanji-agent-0.1.0.jar'
$script:AgentRuntime = Join-Path $script:AgentRoot '.runtime'
$script:AgentState = Join-Path $script:AgentRuntime 'process.json'

function Open-AgentControlLock {
    New-Item -ItemType Directory -Path $script:AgentRuntime -Force | Out-Null
    try {
        return [IO.File]::Open((Join-Path $script:AgentRuntime 'control.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch { throw 'Another start/stop operation is running. Please try again shortly.' }
}

function Get-AgentJava {
    $candidates = @()
    $javaPathFile = Join-Path $script:AgentRoot 'config/java-path.local.txt'
    if (Test-Path -LiteralPath $javaPathFile) {
        $candidates += (Get-Content -LiteralPath $javaPathFile -Raw).Trim()
    }
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin/java.exe' }
    $candidates += @(Get-Command java.exe -All -ErrorAction SilentlyContinue | ForEach-Object { $_.Source })
    $candidates += Join-Path $env:ProgramFiles 'Android/Android Studio/jbr/bin/java.exe'
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not $candidate -or -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $probeInfo = New-Object Diagnostics.ProcessStartInfo
        $probeInfo.FileName = $candidate
        $probeInfo.Arguments = '-version'
        $probeInfo.UseShellExecute = $false
        $probeInfo.CreateNoWindow = $true
        $probeInfo.RedirectStandardError = $true
        $probeInfo.RedirectStandardOutput = $true
        $probe = New-Object Diagnostics.Process
        $probe.StartInfo = $probeInfo
        try {
            [void]$probe.Start()
            $versionText = $probe.StandardError.ReadToEnd() + $probe.StandardOutput.ReadToEnd()
            $probe.WaitForExit()
            $versionMatch = [regex]::Match($versionText, '(?:java|openjdk) version "(\d+)')
            if ($probe.ExitCode -eq 0 -and $versionMatch.Success -and [int]$versionMatch.Groups[1].Value -ge 21) {
                Write-Host ('Using Java ' + $versionMatch.Groups[1].Value + ': ' + $candidate)
                return $candidate
            }
        } catch { } finally { $probe.Dispose() }
    }
    throw 'Java 21 or newer is required. Put its java.exe absolute path in config/java-path.local.txt, then start again.'
}

function Get-ManagedAgent {
    if (-not (Test-Path -LiteralPath $script:AgentState)) { return $null }
    $state = Get-Content -LiteralPath $script:AgentState -Raw | ConvertFrom-Json
    $managed = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [int]$state.process_id) -ErrorAction SilentlyContinue
    if (-not $managed) { return $null }
    $expectedJar = [regex]::Escape($script:AgentJar)
    $isOurCommand = $managed.CommandLine -match ('(?i)(?:^|\s)-jar\s+"?' + $expectedJar + '"?(?:\s|$)')
    # PowerShell 7 deserializes ISO JSON dates as DateTime; Windows PowerShell keeps strings.
    if ($state.created_at -is [DateTime]) {
        $savedCreation = [DateTimeOffset]$state.created_at
    } else {
        $savedCreation = [DateTimeOffset]::Parse(
            ([string]$state.created_at), [Globalization.CultureInfo]::InvariantCulture)
    }
    $sameCreation = $managed.CreationDate.ToUniversalTime().Ticks -eq $savedCreation.UtcDateTime.Ticks
    if ($isOurCommand -and $sameCreation -and $managed.Name -eq 'java.exe') { return $managed }
    throw 'Saved PID belongs to a different process. Refusing to stop or overwrite it. Check .runtime/process.json.'
}
