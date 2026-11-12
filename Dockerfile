# Runs the Student Management System (ApiServer + web files) in the cloud.
# Works on Railway, Render, Fly.io or any Docker host.
FROM eclipse-temurin:21-jdk

WORKDIR /app

# MySQL driver (downloaded here, so you don't need to commit the .jar)
ADD https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar /app/mysql-connector-j.jar

# API and web application files (exclude standalone console exercises and tests).
COPY StudentManagementSystem.java ApiServer.java ClassroomApi.java ClassroomService.java ./
COPY index.html app.js features.js config.js ./

RUN javac -cp ".:mysql-connector-j.jar" StudentManagementSystem.java ApiServer.java ClassroomApi.java ClassroomService.java

# The host sets PORT automatically; ApiServer then listens on all addresses.
# Low-memory flags stop small free plans from killing the app (which shows up as a 502).
CMD ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=70", "-cp", ".:mysql-connector-j.jar", "ApiServer"]
