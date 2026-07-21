# syntax=docker/dockerfile:1.7

########## build: все Java-модули ##########
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY vector-index-common/pom.xml vector-index-common/
COPY vector-index-node/pom.xml   vector-index-node/
COPY index-vector-server/pom.xml index-vector-server/
COPY gateway-service/pom.xml     gateway-service/
COPY telegram-backend/pom.xml    telegram-backend/
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -DskipTests install && \
    mvn -q -B -pl vector-index-node dependency:copy-dependencies \
        -DincludeScope=runtime -DexcludeGroupIds=org.apache.ignite \
        -DoutputDirectory=/out/node-libs && \
    cp vector-index-node/target/vector-index-node-*.jar   /out/node-libs/ && \
    cp vector-index-common/target/vector-index-common-*.jar /out/node-libs/

########## узел Ignite (Java 17, текущая конфигурация) ##########
FROM apacheignite/ignite:2.18.0 AS ignite-node
USER root
RUN apk add --no-cache openjdk17-jre
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk
ENV PATH=/usr/lib/jvm/java-17-openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
COPY --from=build /out/node-libs/ /opt/ignite/apache-ignite/libs/ext/
COPY docker/config/ignite-config.xml /config/ignite-config.xml
ENV CONFIG_URI=/config/ignite-config.xml

########## узел Ignite (Java 21 + SIMD) - включается одной строкой в .env.example ##########
FROM eclipse-temurin:21-jdk-alpine AS jdk21
FROM apacheignite/ignite:2.18.0 AS ignite-node-21
USER root
COPY --from=jdk21 /opt/java/openjdk /opt/java/openjdk-21
ENV JAVA_HOME=/opt/java/openjdk-21
ENV PATH=/opt/java/openjdk-21/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
COPY --from=build /out/node-libs/ /opt/ignite/apache-ignite/libs/ext/
COPY docker/config/ignite-config.xml /config/ignite-config.xml
ENV CONFIG_URI=/config/ignite-config.xml

########## index-vector-server (он же образ бенча) ##########
FROM eclipse-temurin:17-jre AS vector-server
COPY --from=build /src/index-vector-server/target/index-vector-server-*.jar /app/app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]

########## gateway ##########
FROM eclipse-temurin:17-jre AS gateway
COPY --from=build /src/gateway-service/target/gateway-service-*.jar /app/app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]

########## telegram-bot ##########
FROM eclipse-temurin:17-jre AS bot
COPY --from=build /src/telegram-backend/target/telegram-backend-*.jar /app/app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]

########## CLIP ##########
FROM python:3.11-slim AS clip
WORKDIR /app
COPY embedding/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY embedding/ .
# при желании: RUN python download_model.py  (модель запечётся в образ)
ENTRYPOINT ["python","clip_service.py"]