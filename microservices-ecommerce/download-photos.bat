@echo off
REM Downloads the real product photos (needs internet once). Run again any time; it skips photos you already have.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0download-photos.ps1"
pause
