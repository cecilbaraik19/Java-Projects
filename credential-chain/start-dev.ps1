<#
  CredentialChain - start everything with ONE command (development mode).

  Usage (from the credential-chain folder):
      .\start-dev.ps1            # backend (H2 database) + frontend
      .\start-dev.ps1 -Mysql     # backend with MySQL + frontend
      .\start-dev.ps1 -NoBrowser # don't open Chrome automatically

  If Windows blocks the script, run it like this once:
      powershell -ExecutionPolicy Bypass -File .\start-dev.ps1

  Two new windows open: "CredentialChain backend" and "CredentialChain frontend".
  To stop, close those two windows (or press Ctrl + C in each).
#>
param(
    [switch]$Mysql,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$root     = $PSScriptRoot
$backend  = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'

function Info($msg)  { Write-Host "  $msg" -ForegroundColor Cyan }
function Ok($msg)    { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Fail($msg)  { Write-Host "  [X]  $msg" -ForegroundColor Red; Read-Host "  Press Enter to close"; exit 1 }

# Opens a new console window (cmd /k keeps it open so errors stay visible).
# Settings are passed as environment variables, which the new window inherits,
# so folder names with spaces (like "Java Projects") cause no quoting problems.
function Start-Console([string]$title, [string]$workDir, [string]$exe, [string]$exeArgs) {
    $line = "/k `"title $title & call `"$exe`" $exeArgs`""
    Start-Process -FilePath $env:ComSpec -ArgumentList $line -WorkingDirectory $workDir
}

function Test-Port([int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect('127.0.0.1', $port); return $true } catch { return $false } finally { $client.Close() }
}

Write-Host ""
Write-Host "  CredentialChain - starting development servers" -ForegroundColor Magenta
Write-Host ""

# ---------- 1. Check Java and Node ----------
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Fail "Java not found. Install JDK 17 or newer." }
Ok "Java found"
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { Fail "Node.js / npm not found. Install Node.js 18 or newer." }
Ok "Node.js found"

# ---------- 2. Find Maven (PATH, or the copy bundled with IntelliJ) ----------
$mvn = $null
$cmd = Get-Command mvn -ErrorAction SilentlyContinue
if ($cmd) { $mvn = $cmd.Source }
if (-not $mvn) {
    $searchRoots = @("$env:ProgramFiles\JetBrains", "${env:ProgramFiles(x86)}\JetBrains", "$env:LOCALAPPDATA\Programs", "$env:LOCALAPPDATA\JetBrains\Toolbox\apps")
    foreach ($dir in $searchRoots) {
        if (-not (Test-Path $dir)) { continue }
        $found = Get-ChildItem -Path $dir -Filter 'mvn.cmd' -Recurse -ErrorAction SilentlyContinue |
                 Where-Object { $_.FullName -like '*maven3*' } |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($found) { $mvn = $found.FullName; break }
    }
}
if (-not $mvn) { Fail "Maven not found. Install it with:  winget install Apache.Maven  (then open a new terminal)" }
Ok "Maven found: $mvn"

# ---------- 3. Make sure the ports are free ----------
if (Test-Port 8080) { Fail "Port 8080 is already in use. Stop the backend running in IntelliJ (red stop button) and try again." }
if (Test-Port 5173) { Fail "Port 5173 is already in use. Close the other 'npm run dev' window and try again." }
Ok "Ports 8080 and 5173 are free"

# ---------- 4. Start the backend in its own window ----------
if ($Mysql) {
    Info "Database: MySQL"
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    Remove-Item Env:SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
} else {
    Info "Database: H2 (data folder: $root\data)"
    # same 'data' folder that IntelliJ uses, so existing certificates are kept
    $dbPath = (Join-Path $root 'data\credchain') -replace '\\', '/'
    $env:SPRING_DATASOURCE_URL = "jdbc:h2:file:$dbPath;AUTO_SERVER=TRUE"
    Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
}
Start-Console 'CredentialChain backend' $backend $mvn 'spring-boot:run'
Ok "Backend starting in a new window..."

# ---------- 5. Start the frontend in its own window ----------
$npm = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
if (-not $npm) { Fail "npm.cmd not found. Reinstall Node.js." }
if (-not (Test-Path (Join-Path $frontend 'node_modules'))) {
    Info "First run: installing frontend packages (1-2 minutes)..."
    Push-Location $frontend
    & $npm install
    Pop-Location
}
Start-Console 'CredentialChain frontend' $frontend $npm 'run dev'
Ok "Frontend starting in a new window..."

# ---------- 6. Wait until both are ready ----------
Info "Waiting for the backend (first start can take 1-2 minutes)..."
$deadline = (Get-Date).AddMinutes(5)
while (-not (Test-Port 8080)) {
    if ((Get-Date) -gt $deadline) { Fail "Backend did not start in 5 minutes. Look at the 'CredentialChain backend' window for the error." }
    Start-Sleep -Seconds 2
}
Ok "Backend is running on http://localhost:8080"

$deadline = (Get-Date).AddMinutes(3)
while (-not (Test-Port 5173)) {
    if ((Get-Date) -gt $deadline) { Fail "Frontend did not start. Look at the 'CredentialChain frontend' window for the error." }
    Start-Sleep -Seconds 1
}
Ok "Frontend is running on http://localhost:5173"

if (-not $NoBrowser) { Start-Process 'http://localhost:5173' }

Write-Host ""
Write-Host "  Ready! Open http://localhost:5173" -ForegroundColor Green
Write-Host "  To stop: close the 'CredentialChain backend' and 'CredentialChain frontend' windows." -ForegroundColor Gray
Write-Host ""
