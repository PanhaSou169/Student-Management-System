@echo off
setlocal
cd /d "%~dp0"

set "PORT=%~1"
if not defined PORT set "PORT=8080"

where java >nul 2>nul
if errorlevel 1 (
    echo ERROR: Java was not found. Install a JDK and add its bin folder to PATH.
    pause
    exit /b 1
)
where javac >nul 2>nul
if errorlevel 1 (
    echo ERROR: javac was not found. Install a JDK and add its bin folder to PATH.
    pause
    exit /b 1
)

set "MYSQL_JAR=%~dp0..\lib\mysql-connector-j-8.4.0.jar"
if not exist "%MYSQL_JAR%" (
    echo ERROR: MySQL Connector/J was not found:
    echo "%MYSQL_JAR%"
    echo Make sure the project lib folder contains mysql-connector-j-8.4.0.jar.
    pause
    exit /b 1
)

javac -cp ".;%MYSQL_JAR%" StudentManagementSystem.java ApiServer.java ClassroomApi.java ClassroomService.java
if errorlevel 1 (
    echo.
    echo ERROR: The project did not compile. Read the javac messages above.
    pause
    exit /b 1
)

set "SMS_LOCAL_RUN=true"
set "SMS_OPEN_BROWSER=true"
echo Enter your MySQL password when prompted. The browser opens after database setup succeeds.
echo Keep this terminal open while using the app. Press Ctrl+C to stop the server.
java -cp ".;%MYSQL_JAR%" ApiServer %PORT%
set "SERVER_EXIT=%ERRORLEVEL%"
if not "%SERVER_EXIT%"=="0" (
    echo.
    echo ERROR: The server stopped with exit code %SERVER_EXIT%.
    echo Check the error above. Common causes are a wrong MySQL password,
    echo MySQL not running, or port %PORT% already being in use.
    pause
    exit /b %SERVER_EXIT%
)
