# Downloads Apache Maven into .mvn\ the first time, then runs it with the given arguments.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$version = '3.9.9'
$home_ = Join-Path $PSScriptRoot ".mvn\apache-maven-$version"
$mvn = Join-Path $home_ 'bin\mvn.cmd'

# 1. Java check
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java -and -not $env:JAVA_HOME) {
    Write-Host ''
    Write-Host 'Java is not installed (or not on PATH).' -ForegroundColor Red
    Write-Host 'Install JDK 21 from https://adoptium.net (tick "Set JAVA_HOME" during setup), then open a NEW PowerShell window and try again.'
    exit 1
}

# 2. Download Maven once
if (-not (Test-Path $mvn)) {
    Write-Host "First run: downloading Maven $version (about 9 MB)..." -ForegroundColor Cyan
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $zip = Join-Path $env:TEMP "apache-maven-$version-bin.zip"
    Invoke-WebRequest "https://archive.apache.org/dist/maven/maven-3/$version/binaries/apache-maven-$version-bin.zip" -OutFile $zip -UseBasicParsing
    New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot '.mvn') | Out-Null
    Expand-Archive $zip -DestinationPath (Join-Path $PSScriptRoot '.mvn') -Force
    Remove-Item $zip
    Write-Host 'Maven ready.' -ForegroundColor Green
}

# 3. Run it
Push-Location $PSScriptRoot
try { & $mvn @args; exit $LASTEXITCODE } finally { Pop-Location }
