@echo off
REM Compile and run all backend tests. No JUnit download needed.
setlocal enabledelayedexpansion
cd /d "%~dp0"

if exist out rmdir /s /q out
mkdir out\classes out\test
(for /r src\main\java %%f in (*.java) do (set "p=%%f" & echo "!p:\=/!")) > out\sources.txt
(for /r src\test\java %%f in (*.java) do (set "p=%%f" & echo "!p:\=/!")) > out\test-sources.txt
javac -encoding UTF-8 -d out\classes @out\sources.txt || (echo Build failed. & pause & exit /b 1)
javac -encoding UTF-8 -cp out\classes -d out\test @out\test-sources.txt || (echo Test build failed. & pause & exit /b 1)
java -cp "out\classes;out\test" com.cecil.energy.AllTests
echo.
pause
