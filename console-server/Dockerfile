# 控制面后端（Spring Boot 4.2 / JDK 21）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 构建期走阿里云镜像：本机网络直连 Maven Central 返回 403
COPY maven-settings.xml /tmp/maven-settings.xml
COPY pom.xml .
COPY src ./src
# BuildKit 缓存挂载：重复构建不再重新下载依赖
RUN --mount=type=cache,target=/root/.m2 \
    mvn -s /tmp/maven-settings.xml -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r -g 10001 console \
    && useradd -r -u 10001 -g 10001 console \
    && mkdir -p /app/backups \
    && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /build/target/tiering-kv-console-*.jar /app/console.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/console.jar"]
