FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build
# Keep the wrapper on its checksum-pinned ZIP distribution on Linux too.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/main/ src/main/
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/flight-board-backend-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080 8081 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
