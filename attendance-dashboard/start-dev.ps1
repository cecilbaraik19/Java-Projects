# Starts the backend and frontend in two new PowerShell windows (development mode).
# Usage (from the project folder):   .\start-dev.ps1
# If Windows blocks the script:      powershell -ExecutionPolicy Bypass -File .\start-dev.ps1

$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Free port 8080 if an old backend is still running
$old = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($old) {
    Write-Host "Stopping old process on port 8080 (PID $($old.OwningProcess))..."
    Stop-Process -Id $old.OwningProcess -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host "Maven (mvn) was not found on PATH. Install it first - see README." -ForegroundColor Red
    exit 1
}
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    Write-Host "Node.js (npm) was not found on PATH. Install Node.js 18+ first." -ForegroundColor Red
    exit 1
}

Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$Host.UI.RawUI.WindowTitle='Backend :8080'; Set-Location '$root\backend'; mvn spring-boot:run"

$frontend = "$root\frontend"
$install = if (Test-Path "$frontend\node_modules") { "" } else { "npm install; " }
Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$Host.UI.RawUI.WindowTitle='Frontend :5173'; Set-Location '$frontend'; ${install}npm run dev"

Write-Host ""
Write-Host "Backend and frontend are starting in two new windows." -ForegroundColor Green
Write-Host "Open http://localhost:5173 once the backend says 'Started AttendanceApplication'."
Write-Host "Log in with admin / Admin@123. Close the two windows to stop."
