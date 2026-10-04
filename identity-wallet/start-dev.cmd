@echo off
REM Double-click to start IdentityWallet (works even when PowerShell scripts are blocked)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-dev.ps1"
pause
