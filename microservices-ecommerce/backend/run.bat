@echo off
REM Build and start ALL 10 services (the gateway also serves the website from ..\frontend).
REM Needs only Java 17 or newer. No internet or extra libraries required.
setlocal enabledelayedexpansion
cd /d "%~dp0"

where javac >nul 2>nul || (echo Java JDK 17+ not found. Install it and make sure "javac" is on PATH. & pause & exit /b 1)

echo Compiling backend...
if exist out rmdir /s /q out
mkdir out\classes
(for /r src\main\java %%f in (*.java) do (set "p=%%f" & echo "!p:\=/!")) > out\sources.txt
javac -encoding UTF-8 -d out\classes @out\sources.txt
if errorlevel 1 (echo Build failed. & pause & exit /b 1)
jar --create --file out\microservices-ecommerce.jar --main-class com.cecil.shop.App -C out\classes .

echo Starting 10 services... open http://localhost:8080 in your browser. Press Ctrl+C to stop.
java -jar out\microservices-ecommerce.jar
pause
