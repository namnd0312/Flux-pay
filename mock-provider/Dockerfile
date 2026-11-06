# Multi-stage image. Compose sets the build context to the repo root.
FROM eclipse-temurin:21-jdk AS stage1
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY gateway/pom.xml gateway/pom.xml
COPY mock-provider/pom.xml mock-provider/pom.xml
RUN ./mvnw -q -pl mock-provider -am dependency:go-offline
COPY mock-provider/src mock-provider/src
RUN ./mvnw -q -pl mock-provider -am clean package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=stage1 /workspace/mock-provider/target/mock-provider-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
