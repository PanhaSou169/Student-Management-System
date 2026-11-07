#!/bin/sh
# Start the Student Management System (Mac/Linux). Put mysql-connector-j-*.jar in this folder.
JAR=$(ls mysql-connector-j*.jar 2>/dev/null | head -n1)
if [ -z "$JAR" ]; then
  echo "Put mysql-connector-j-x.y.z.jar in this folder first."
  exit 1
fi
javac -cp ".:$JAR" *.java || exit 1
echo "Open http://localhost:8080 in your browser"
java -cp ".:$JAR" ApiServer
