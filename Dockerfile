# Runs the Student Management System (ApiServer + web files) in the cloud.
# Works on Railway, Render, Fly.io or any Docker host.
FROM eclipse-temurin:21-jdk

WORKDIR /app

# MySQL driver (downloaded here, so you don't need to commit the .jar)
ADD https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar /app/mysql-connector-j.jar

# Copy the whole repo, then pick the project files up from WHEREVER they are
# (root folder or a sub-folder such as Java/). Keep only ONE copy of each file name.
COPY . /src
RUN find /src -type f \( -name "*.java" -o -name "index.html" -o -name "app.js" \
        -o -name "features.js" -o -name "config.js" \) -exec cp -f {} /app/ \; \
 && echo "---- files in /app ----" && ls -la /app \
 && javac -cp ".:mysql-connector-j.jar" *.java

# The host sets PORT automatically; ApiServer then listens on all addresses.
# Low-memory flags stop small free plans from killing the app (which shows up as a 502).
CMD ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=70", "-cp", ".:mysql-connector-j.jar", "ApiServer"]
