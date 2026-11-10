# Notification service image. Build context: backend/  (see docker-compose.yml)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY notification-service/pom.xml notification-service/pom.xml
# Maven reads every module listed in the parent pom, so the other service's pom must exist (no code is copied).
COPY core-service/pom.xml core-service/pom.xml
RUN chmod +x mvnw && ./mvnw -B -q -pl notification-service -am dependency:go-offline
COPY notification-service/src notification-service/src
RUN ./mvnw -B -q -pl notification-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 shelfwise
COPY --from=build /src/notification-service/target/notification-service-*.jar app.jar
USER shelfwise
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
