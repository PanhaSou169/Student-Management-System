@echo off
rem Start the Student Management System (Windows). Put mysql-connector-j-*.jar in this folder.
set JAR=
for %%f in (mysql-connector-j*.jar) do set JAR=%%f
if "%JAR%"=="" (
  echo Put mysql-connector-j-x.y.z.jar in this folder first.
  pause
  exit /b 1
)
javac -cp ".;%JAR%" *.java
if errorlevel 1 (
  pause
  exit /b 1
)
echo Open http://localhost:8080 in your browser
java -cp ".;%JAR%" ApiServer
pause
