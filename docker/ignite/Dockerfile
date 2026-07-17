FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY vector-index-common vector-index-common
COPY vector-index-node   vector-index-node
RUN mvn -f vector-index-common/pom.xml install -DskipTests && \
    mvn -f vector-index-node/pom.xml   package -DskipTests

FROM apacheignite/ignite:2.18.0
USER root
RUN apk add --no-cache openjdk17-jre netcat-openbsd
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk
ENV PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH
COPY --from=build /src/vector-index-common/target/vector-index-common-*.jar /opt/ignite/apache-ignite/libs/
COPY --from=build /src/vector-index-node/target/vector-index-node-*.jar     /opt/ignite/apache-ignite/libs/
COPY docker/config/ignite-config.xml /opt/ignite/config/ignite-config.xml