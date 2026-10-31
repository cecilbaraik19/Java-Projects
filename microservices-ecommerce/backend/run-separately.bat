@echo off
REM Starts EVERY service in its own window - 10 separate Java programs, like 10 separate servers.
REM Close a window to "crash" that service and watch the rest of the shop keep working.
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

echo Starting the registry first...
start "registry :8761" cmd /k java -jar out\microservices-ecommerce.jar registry
timeout /t 3 /nobreak >nul
for %%s in (event-bus auth-service catalog-service inventory-service cart-service order-service payment-service notification-service api-gateway) do (
  start "%%s" cmd /k java -jar out\microservices-ecommerce.jar %%s
  timeout /t 1 /nobreak >nul
)
echo.
echo 10 windows opened. Wait a few seconds, then open http://localhost:8080
pause
