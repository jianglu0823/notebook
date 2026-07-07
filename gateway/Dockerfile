# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY infra/maven-settings.xml /root/.m2/settings.xml
COPY gateway/pom.xml ./pom.xml
RUN mvn -q -B dependency:go-offline || true
COPY gateway/src ./src
RUN mvn -q -B -DskipTests package

# ---------- run ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/gateway-*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
