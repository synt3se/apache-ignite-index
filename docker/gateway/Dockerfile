# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY vector-index-common vector-index-common
COPY gateway-service     gateway-service
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -f vector-index-common/pom.xml install -DskipTests && \
    mvn -q -f gateway-service/pom.xml     package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/gateway-service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
