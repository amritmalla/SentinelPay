# syntax=docker/dockerfile:1
# Multi-stage build. Build context is the repository root (monorepo) so the parent POM and
# shared modules (sentinelpay-common) are available.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl notification-service -am -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/notification-service/target/*.jar app.jar
USER app
EXPOSE 8084
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
