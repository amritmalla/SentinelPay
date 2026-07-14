# syntax=docker/dockerfile:1
# Multi-stage build. Build context is the repository root (monorepo) so the parent POM and
# shared modules (sentinelpay-common) are available.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl provider-service -am -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
    && apk add --no-cache curl
WORKDIR /app
COPY --from=build /workspace/provider-service/target/*.jar app.jar
USER app
EXPOSE 8085
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=4 \
    CMD curl -fsS http://localhost:8085/actuator/health/liveness || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
