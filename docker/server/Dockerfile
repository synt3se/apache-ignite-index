# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY vector-index-common vector-index-common
COPY vector-index-node   vector-index-node
COPY index-vector-server index-vector-server
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -f vector-index-common/pom.xml install -DskipTests && \
    mvn -q -f vector-index-node/pom.xml   install -DskipTests && \
    mvn -q -f index-vector-server/pom.xml package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/index-vector-server/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","app.jar"]
