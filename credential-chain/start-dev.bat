@echo off
REM Double-click to start CredentialChain (backend + frontend)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-dev.ps1" %*
