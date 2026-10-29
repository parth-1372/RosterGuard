FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 rosterguard
COPY --from=build /build/target/rosterguard-*.jar app.jar
USER rosterguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

