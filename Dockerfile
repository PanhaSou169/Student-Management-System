# Runs the Student Management System (ApiServer + web files) in the cloud.
# Works on Railway, Render, Fly.io or any Docker host.
FROM eclipse-temurin:21-jdk

WORKDIR /app

# MySQL driver (downloaded here, so you don't need to commit the .jar)
ADD https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar /app/mysql-connector-j.jar

# your project files
COPY *.java *.html *.js ./

RUN javac -cp ".:mysql-connector-j.jar" *.java

# the host sets PORT automatically; ApiServer then listens on all addresses
CMD ["java", "-cp", ".:mysql-connector-j.jar", "ApiServer"]
