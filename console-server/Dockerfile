# 控制面后端（Spring Boot 3.3 / JDK 21）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd -r console && useradd -r -g console console
COPY --from=build /build/target/tiering-kv-console-*.jar /app/console.jar
RUN mkdir -p /app/backups && chown -R console:console /app
USER console
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/console.jar"]
