# Core service image. Build context: the REPOSITORY ROOT, so the provided dataset (data/) can go into the image for the
# import on start-up (Render builds the same way; see render.yaml and docs/deploy.md). docker-compose.yml uses this context too.
# Stage 1 builds the jar with the Maven wrapper; stage 2 runs it on a slim Java runtime.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Build files first, so dependency downloads are cached between code changes.
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn .mvn
COPY backend/core-service/pom.xml core-service/pom.xml
# Maven reads every module listed in the parent pom, so the other service's pom must exist (no code is copied).
COPY backend/notification-service/pom.xml notification-service/pom.xml
RUN chmod +x mvnw && ./mvnw -B -q -pl core-service -am dependency:go-offline
COPY backend/core-service/src core-service/src
RUN ./mvnw -B -q -pl core-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 shelfwise
COPY --from=build /src/core-service/target/core-service-*.jar app.jar
# The provided dataset, exactly as received, read by the import when IMPORT_ON_STARTUP=true (re-runs add only new records, D28).
COPY data /data
ENV IMPORT_DATA_DIR=/data
USER shelfwise
EXPOSE 8080
# Use most of the container's memory for the heap (the JVM default is a quarter).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
