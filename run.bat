@echo off
setlocal
cd /d "%~dp0"

set "SQLITE_JAR=%~dp0sqlite-jdbc-3.53.4.0.jar"
if not exist "%SQLITE_JAR%" (
    echo SQLite JDBC driver not found: "%SQLITE_JAR%"
    exit /b 1
)

set "MYSQL_JAR=%~dp0..\lib\mysql-connector-j-8.4.0.jar"
if not exist "%MYSQL_JAR%" (
    echo MySQL Connector/J not found: "%MYSQL_JAR%"
    exit /b 1
)

javac -cp "%SQLITE_JAR%;%MYSQL_JAR%" StudentManagementSystem.java
if errorlevel 1 exit /b 1

java -cp ".;%SQLITE_JAR%;%MYSQL_JAR%" StudentManagementSystem
