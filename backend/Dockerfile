# syntax=docker/dockerfile:1
#
# Build context is the REPOSITORY ROOT (see docker-compose.yml / fly.toml):
#   docker build -f backend/Dockerfile -t taskhub-api .
#
# ---------------------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first so a source-only change reuses this layer.
COPY backend/pom.xml ./pom.xml
RUN mvn -B -q -DskipTests dependency:go-offline

COPY backend/src ./src
RUN mvn -B -q -DskipTests clean package \
    && cp target/taskhub-api.jar /build/app.jar

# ---------------------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S taskhub && adduser -S -G taskhub -h /app taskhub
WORKDIR /app

COPY --from=build --chown=taskhub:taskhub /build/app.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE=prod \
    PORT=8080

USER taskhub
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health/readiness" >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
