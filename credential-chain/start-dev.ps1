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

# Opens a new PowerShell window running $script. -EncodedCommand avoids quoting problems with
# folder names that contain spaces (like "Java Projects").
function Start-Window([string]$script) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
    Start-Process powershell -ArgumentList '-NoExit', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded
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
$profileArg = ''
if ($Mysql) { $profileArg = "'-Dspring-boot.run.profiles=mysql'"; Info "Database: MySQL" } else { Info "Database: H2 (default)" }

# workingDirectory = project root, so the H2 database is the same 'data' folder IntelliJ uses
$backendCmd = "`$Host.UI.RawUI.WindowTitle = 'CredentialChain backend'; Set-Location -LiteralPath '$backend'; & '$mvn' spring-boot:run '-Dspring-boot.run.workingDirectory=$root' $profileArg"
Start-Window $backendCmd
Ok "Backend starting in a new window..."

# ---------- 5. Start the frontend in its own window ----------
$install = ''
if (-not (Test-Path (Join-Path $frontend 'node_modules'))) { $install = 'npm install; '; Info "First run: installing frontend packages" }
$frontendCmd = "`$Host.UI.RawUI.WindowTitle = 'CredentialChain frontend'; Set-Location -LiteralPath '$frontend'; ${install}npm run dev"
Start-Window $frontendCmd
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
