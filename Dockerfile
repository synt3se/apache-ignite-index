# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /src
COPY . .
# cache mount: зависимости качаются при первой сборке, дальше - из кэша
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -DskipTests -f vector-index-common install && \
    mvn -q -DskipTests -f vector-index-node install && \
    mvn -q -DskipTests -f vector-index-node dependency:copy-dependencies \
        -DincludeScope=runtime -DexcludeGroupIds=org.apache.ignite && \
    mvn -q -DskipTests -f index-vector-server package && \
    mvn -q -DskipTests -f gateway-service package && \
    mvn -q -DskipTests -f telegram-backend package

# ---------- узел Ignite: библиотеки запечены в образ ----------
# Полный JDK (не -jre): jcmd/jfr нужны для Flight Recorder.
FROM apacheignite/ignite:2.18.0 AS node
USER root
RUN apk add --no-cache openjdk17
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk
ENV PATH=/usr/lib/jvm/java-17-openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
COPY --from=builder /src/vector-index-common/target/vector-index-common-*.jar /opt/ignite/apache-ignite/libs/ext/
COPY --from=builder /src/vector-index-node/target/vector-index-node-*.jar     /opt/ignite/apache-ignite/libs/ext/
COPY --from=builder /src/vector-index-node/target/dependency/*.jar            /opt/ignite/apache-ignite/libs/ext/

# ---------- приложения ----------
FROM eclipse-temurin:17-jre AS server
COPY --from=builder /src/index-vector-server/target/*.jar /app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app.jar"]

FROM eclipse-temurin:17-jre AS gateway
COPY --from=builder /src/gateway-service/target/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx384m", "-jar", "/app.jar"]

FROM eclipse-temurin:17-jre AS bot
COPY --from=builder /src/telegram-backend/target/*.jar /app.jar
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app.jar"]