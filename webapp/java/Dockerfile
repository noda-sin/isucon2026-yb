FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY webapp/java/pom.xml ./
RUN mvn -B dependency:go-offline
COPY webapp/java/src ./src
RUN mvn -B package
FROM eclipse-temurin:21.0.7_6-jre-alpine
WORKDIR /app
COPY --from=build /src/target/isureal-java-1.0.0.jar /app/isureal.jar
USER 65532:65532
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=10s --start-period=45s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/isureal.jar"]
