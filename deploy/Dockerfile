FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# 注意：不能用 dependency:go-offline —— 它无法解析 netty-tcnative 的
# ${os.detected.classifier}（os-maven-plugin 扩展在 go-offline 阶段不生效，
# Phase 21 真实容器验证发现）。直接 package 走正常生命周期即可解析。
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/tiering-kv-*.jar /app/tiering-kv.jar
ENTRYPOINT ["java", "-Xmx1g", "-jar", "/app/tiering-kv.jar"]
