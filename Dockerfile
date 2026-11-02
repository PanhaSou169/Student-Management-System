FROM eclipse-temurin:21-jdk
WORKDIR /app
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/* \
 && curl -fL -o mysql-connector-j.jar https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar
COPY *.java index.html app.js ./
RUN javac -cp mysql-connector-j.jar *.java
CMD ["java", "-cp", ".:mysql-connector-j.jar", "ApiServer"]
