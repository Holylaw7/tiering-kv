# 控制面后端（Spring Boot 3.3 / JDK 21）
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
RUN groupadd -r console && useradd -r -g console console
COPY --from=build /build/target/tiering-kv-console-*.jar /app/console.jar
RUN mkdir -p /app/backups && chown -R console:console /app
USER console
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/console.jar"]
