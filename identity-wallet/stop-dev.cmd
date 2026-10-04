@echo off
REM Double-click to stop IdentityWallet
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-dev.ps1"
pause
