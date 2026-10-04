<#
  IdentityWallet - one-click start
  Starts the backend (port 8080) and the website (port 5173) in two windows,
  waits until they are ready and opens the browser.

  Usage (from the identity-wallet folder):   .\start-dev.ps1
  If PowerShell blocks scripts, double-click start-dev.cmd instead.
  To stop everything:                          .\stop-dev.ps1   (or close the two windows)
#>
$ErrorActionPreference = 'Stop'
$root     = $PSScriptRoot
$backend  = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend'

function Test-Port([int]$port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

Write-Host ''
Write-Host '  IdentityWallet - starting up' -ForegroundColor Cyan
Write-Host '  ----------------------------' -ForegroundColor Cyan

# 1. Check tools
if (-not (Get-Command java -ErrorAction SilentlyContinue) -and -not $env:JAVA_HOME) {
    Write-Host '  Java was not found. Install JDK 21 from https://adoptium.net (tick "Set JAVA_HOME"), then open a new PowerShell.' -ForegroundColor Red
    exit 1
}
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    Write-Host '  Node.js / npm was not found. Install Node.js LTS from https://nodejs.org, then open a new PowerShell.' -ForegroundColor Red
    exit 1
}

# 2. Website packages (first run only)
if (-not (Test-Path (Join-Path $frontend 'node_modules'))) {
    Write-Host '  Installing website packages (first run only, 1-2 minutes)...' -ForegroundColor Yellow
    Push-Location $frontend
    try { npm install } finally { Pop-Location }
}

# 3. Backend window
if (Test-Port 8080) {
    Write-Host '  Backend is already running on port 8080.' -ForegroundColor Green
} else {
    Write-Host '  Starting backend in a new window...'
    Start-Process powershell -WorkingDirectory $backend -ArgumentList @(
        '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command',
        "`$Host.UI.RawUI.WindowTitle = 'IdentityWallet - Backend (8080)'; .\mvnw.cmd spring-boot:run")
}

# 4. Website window
if (Test-Port 5173) {
    Write-Host '  Website is already running on port 5173.' -ForegroundColor Green
} else {
    Write-Host '  Starting website in a new window...'
    Start-Process powershell -WorkingDirectory $frontend -ArgumentList @(
        '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command',
        "`$Host.UI.RawUI.WindowTitle = 'IdentityWallet - Website (5173)'; npm run dev")
}

# 5. Wait until the backend answers (first run downloads Maven + libraries)
Write-Host '  Waiting for the backend to be ready (first run can take a few minutes)' -NoNewline
$deadline = (Get-Date).AddMinutes(8)
$ready = $false
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri 'http://localhost:8080/api/public/issuers' -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200 -and (Test-Port 5173)) { $ready = $true; break }
    } catch { }
    Write-Host '.' -NoNewline
    Start-Sleep -Seconds 3
}
Write-Host ''

if ($ready) {
    Write-Host '  Ready! Opening http://localhost:5173' -ForegroundColor Green
    Start-Process 'http://localhost:5173'
    Write-Host ''
    Write-Host '  Demo logins:'
    Write-Host '    Wallet holder : aarav@wallet.demo        / User@1234'
    Write-Host '    Issuer        : registrar@identity.demo  / Issuer@123'
    Write-Host '    Admin         : admin@idwallet.local     / Admin@123'
    Write-Host ''
    Write-Host '  Keep the two new windows open. To stop: .\stop-dev.ps1 or close them.' -ForegroundColor DarkGray
} else {
    Write-Host '  The backend did not start in time. Look at the "IdentityWallet - Backend" window for the error.' -ForegroundColor Red
    exit 1
}
