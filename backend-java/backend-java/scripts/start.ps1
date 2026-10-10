param(
    [string]$Profiles = 'mysql,local',
    [int]$Port = 8000,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$backendJavaRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $backendJavaRoot
try {
    if ($Profiles -match 'mysql') {
        $localConfig = Join-Path $backendJavaRoot 'config/application-local.yml'
        if ((Test-Path -LiteralPath $localConfig) -and ((Get-Content -LiteralPath $localConfig -Raw) -match 'local-db-not-configured')) {
            throw 'Configure MySQL first: run dependence/mysql/configure-mysql.py with the RAG Python runtime.'
        }
    }
    $javaPathFile = Join-Path $backendJavaRoot 'config/java-path.local.txt'
    if (Test-Path -LiteralPath $javaPathFile) {
        $javaExecutable = (Get-Content -LiteralPath $javaPathFile -Raw).Trim()
        if (-not (Test-Path -LiteralPath $javaExecutable)) { throw 'Configured Java executable does not exist.' }
        $env:JAVA_HOME = Split-Path (Split-Path $javaExecutable -Parent) -Parent
        $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    }
    $backendJavaRepo = Join-Path $backendJavaRoot '.m2'
    if ($Build -or -not (Test-Path -LiteralPath 'target/backend-java-1.0.0.jar')) {
        & mvn "-Dmaven.repo.local=$backendJavaRepo" clean verify
        if ($LASTEXITCODE -ne 0) { throw 'Java build or verification failed.' }
    }
    $backendJavaArgs = @('-jar', 'target/backend-java-1.0.0.jar', "--server.port=$Port")
    if ($Profiles) { $backendJavaArgs += "--spring.profiles.active=$Profiles" }
    & java @backendJavaArgs
    if ($LASTEXITCODE -ne 0) { throw 'Java backend exited with an error.' }
} finally {
    Pop-Location
}
