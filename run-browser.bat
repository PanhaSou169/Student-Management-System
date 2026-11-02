@echo off
setlocal
cd /d "%~dp0"

set "PORT=%~1"
if not defined PORT set "PORT=8080"

set "MYSQL_JAR=%~dp0..\lib\mysql-connector-j-8.4.0.jar"
if not exist "%MYSQL_JAR%" (
    echo MySQL Connector/J not found: "%MYSQL_JAR%"
    pause
    exit /b 1
)

javac -cp ".;%MYSQL_JAR%" StudentManagementSystem.java ApiServer.java
if errorlevel 1 (
    pause
    exit /b 1
)

start "Student Management API" /D "%~dp0" java -cp ".;%MYSQL_JAR%" ApiServer %PORT%

powershell -NoProfile -Command "$url = 'http://localhost:%PORT%/'; $deadline = (Get-Date).AddSeconds(90); do { try { $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2; if ($response.StatusCode -eq 200) { exit 0 } } catch { }; Start-Sleep -Seconds 1 } while ((Get-Date) -lt $deadline); exit 1"
if errorlevel 1 (
    echo The server did not become ready. Check the Student Management API window for MySQL or setup errors.
    pause
    exit /b 1
)

set "EDGE=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not exist "%EDGE%" set "EDGE=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
if exist "%EDGE%" (
    start "" "%EDGE%" "http://localhost:%PORT%/"
) else (
    start "" "http://localhost:%PORT%/"
)
