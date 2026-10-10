Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$DeployDir = $PSScriptRoot
$BackendRepo = Split-Path $DeployDir -Parent
$SourceRoot = Split-Path $BackendRepo -Parent
$BackendProject = Join-Path $BackendRepo 'backend-java'
$AgentProject = Join-Path $SourceRoot 'agent-java/agent-java'
$MobileProject = Join-Path $SourceRoot 'frontend-flutter/frontend/apps/mobile'
function Get-DeploymentConfig {
    param([string]$ConfigFile = (Join-Path $DeployDir 'deployment.local.json'))
    if (!(Test-Path -LiteralPath $ConfigFile)) { throw 'Run deploy/setup.ps1 first.' }
    $c = Get-Content -LiteralPath $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($name in @('Database','DatabaseUser')) {
        if ($c.$name -notmatch '^[A-Za-z][A-Za-z0-9_]{0,31}$') { throw "Invalid identifier: $name" }
    }
    foreach ($name in @('MysqlPort','BackendPort','AgentPort','MinioPort','MinioConsolePort')) {
        if ([int]$c.$name -lt 1 -or [int]$c.$name -gt 65535) { throw "Invalid port: $name" }
    }
    return $c
}
function Use-DeploymentJava {
    param($Config)
    if ($Config.JavaHome) {
        $env:JAVA_HOME = $Config.JavaHome
        $env:PATH = (Join-Path $Config.JavaHome 'bin') + ';' + $env:PATH
    }
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $version = (& $java -version 2>&1 | Out-String)
    if ($version -notmatch 'version "(\d+)' -or [int]$Matches[1] -lt 21) { throw 'JDK 21 or newer is required.' }
    return $java
}
function ConvertTo-SqlLiteral {
    param([string]$Value)
    return "'" + $Value.Replace('\','\\').Replace("'","''") + "'"
}
function Invoke-DeploymentSql {
    param($Config, [string]$Sql, [string]$User = '', [AllowEmptyString()][string]$Password = '', [switch]$WithoutDatabase)
    if (!$User) { $User = $Config.DatabaseUser; $Password = $Config.DatabasePassword }
    $savedPassword = $env:MYSQL_PWD
    $savedEncoding = $OutputEncoding
    try {
        $env:MYSQL_PWD = $Password
        $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
        $arguments = @('--protocol=TCP',"--host=$($Config.MysqlHost)","--port=$($Config.MysqlPort)","--user=$User",'--default-character-set=utf8mb4','--batch','--skip-column-names')
        if (!$WithoutDatabase) { $arguments += "--database=$($Config.Database)" }
        $result = $Sql | & $Config.MysqlExe @arguments
        if ($LASTEXITCODE -ne 0) { throw 'MySQL operation failed. Check the preceding database error.' }
        return $result
    } finally { $env:MYSQL_PWD = $savedPassword; $OutputEncoding = $savedEncoding }
}
function Read-PrivateValue {
    param([string]$Prompt)
    $secret = Read-Host $Prompt -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
function New-DeploymentKey {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); return [Convert]::ToBase64String($bytes) }
    finally { $rng.Dispose() }
}
function Set-BackendEnvironment {
    param($Config)
    $env:SERVER_PORT = [string]$Config.BackendPort
    $env:SERVER_ADDRESS = '0.0.0.0'
    $env:MYSQL_HOST = $Config.MysqlHost
    $env:MYSQL_PORT = [string]$Config.MysqlPort
    $env:MYSQL_DATABASE = $Config.Database
    $env:MYSQL_USER = $Config.DatabaseUser
    $env:MYSQL_PASSWORD = $Config.DatabasePassword
    $env:INTERNAL_SERVICE_KEY = $Config.InternalServiceKey
    $env:AGENT_RECEIVER_KEY = $Config.AgentReceiverKey
    $env:AGENT_EXECUTION_MODE = 'external'
    $env:AGENT_DISPATCH_URL = "http://127.0.0.1:$($Config.AgentPort)/internal/v1/tasks"
    $env:STORAGE_BACKEND = 'minio'
    $env:MINIO_ENDPOINT = "http://127.0.0.1:$($Config.MinioPort)"
    $env:MINIO_ACCESS_KEY = $Config.MinioUser
    $env:MINIO_SECRET_KEY = $Config.MinioPassword
    $env:MINIO_BUCKET = 'yanji-files'
    $env:RETRIEVAL_MODE = 'lexical'
    $env:REDIS_ENABLED = 'false'
}
