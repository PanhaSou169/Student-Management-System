FROM eclipse-temurin:21-jdk
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o mysql-connector-j.jar \
      https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar
COPY *.java *.html *.js *.css ./
RUN javac -cp mysql-connector-j.jar *.java
ENV PORT=8080 SMS_DATA_DIR=/app/data TZ=UTC
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health >/dev/null || exit 1
CMD ["java", "-XX:MaxRAMPercentage=75.0", "-cp", ".:mysql-connector-j.jar", "ApiServer"]
