[CmdletBinding()]
param([string]$ConfigFile = '')
. "$PSScriptRoot/common.ps1"
$c = Get-DeploymentConfig $ConfigFile
$java = Use-DeploymentJava $c
Set-BackendEnvironment $c
$jar = Join-Path $BackendProject 'target/backend-java-1.0.0.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Run deploy/build.ps1 first.' }
$libs = Join-Path $DeployDir 'runtime/storage-libs'
$null = New-Item -ItemType Directory -Path $libs -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName.StartsWith('BOOT-INF/lib/') -and $entry.Name.EndsWith('.jar')) {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $libs $entry.Name),$true)
        }
    }
} finally { $archive.Dispose() }
& $java -cp "$libs/*" (Join-Path $DeployDir 'InitStorage.java')
if ($LASTEXITCODE -ne 0) { throw 'Storage initialization failed.' }
