# Core service image. Build context: backend/  (see docker-compose.yml)
# Stage 1 builds the jar with the Maven wrapper; stage 2 runs it on a slim Java runtime.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Build files first, so dependency downloads are cached between code changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY core-service/pom.xml core-service/pom.xml
# Maven reads every module listed in the parent pom, so the other service's pom must exist (no code is copied).
COPY notification-service/pom.xml notification-service/pom.xml
RUN chmod +x mvnw && ./mvnw -B -q -pl core-service -am dependency:go-offline
COPY core-service/src core-service/src
RUN ./mvnw -B -q -pl core-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 shelfwise
COPY --from=build /src/core-service/target/core-service-*.jar app.jar
USER shelfwise
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
