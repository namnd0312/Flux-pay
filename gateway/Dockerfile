# Multi-stage image. Compose sets the build context to the repo root so the
# Maven reactor can see the aggregator pom and both modules.
FROM eclipse-temurin:21-jdk AS stage1
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY gateway/pom.xml gateway/pom.xml
COPY mock-provider/pom.xml mock-provider/pom.xml
# Warm the dependency cache for this module before copying sources.
RUN ./mvnw -q -pl gateway -am dependency:go-offline
COPY gateway/src gateway/src
RUN ./mvnw -q -pl gateway -am clean package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=stage1 /workspace/gateway/target/gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
