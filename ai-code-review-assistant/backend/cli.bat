@echo off
REM Command line version (no website). Examples:
REM   cli.bat                       interactive menu
REM   cli.bat ..\samples            review a folder
REM   cli.bat ..\samples --fix      also write auto-fixed copies
REM   cli.bat --help                all options
setlocal enabledelayedexpansion
cd /d "%~dp0"
if not exist out\code-review.jar (
    if exist out rmdir /s /q out
    mkdir out\classes
    (for /r src\main\java %%f in (*.java) do (set "p=%%f" & echo "!p:\=/!")) > out\sources.txt
    javac -encoding UTF-8 -d out\classes @out\sources.txt || (echo Build failed. & exit /b 1)
    jar --create --file out\code-review.jar --main-class com.cecil.codereview.App -C out\classes .
)
java -jar out\code-review.jar --cli %*
