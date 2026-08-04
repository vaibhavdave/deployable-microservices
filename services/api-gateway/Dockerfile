# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew gradlew.bat build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle
COPY services/product-service ./services/product-service
COPY services/order-service ./services/order-service
COPY services/api-gateway ./services/api-gateway

RUN ./gradlew --no-daemon :services:api-gateway:bootJar

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 1000 spring && useradd --uid 1000 --gid spring --shell /bin/false spring
USER spring:spring

WORKDIR /app
COPY --from=build /workspace/services/api-gateway/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
