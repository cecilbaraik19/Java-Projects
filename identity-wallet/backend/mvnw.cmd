@echo off
REM Runs Maven without installing it. Usage:  .\mvnw.cmd test   or   .\mvnw.cmd spring-boot:run
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0maven.ps1" %*
exit /b %ERRORLEVEL%
