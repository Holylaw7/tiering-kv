FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# 注意：不能用 dependency:go-offline —— 它无法解析 netty-tcnative 的
# ${os.detected.classifier}（os-maven-plugin 扩展在 go-offline 阶段不生效，
# Phase 21 真实容器验证发现）。直接 package 走正常生命周期即可解析。
RUN mvn -q -DskipTests package
# 产物同时存在 tiering-kv-<ver>.jar 与 tiering-kv-<ver>-shaded.jar，
# glob 会匹配多个文件，因此在构建阶段先挑出主构件并固定路径
RUN set -e; \
    jar=$(ls target/tiering-kv-*.jar | grep -v shaded | head -n1); \
    test -n "$jar"; \
    cp "$jar" /tmp/tiering-kv.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
# tc netem 混沌注入（ADR-0343）：真实网络故障演练需要 iproute2。
RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /tmp/tiering-kv.jar /app/tiering-kv.jar
ENTRYPOINT ["java", "-Xmx1g", "-jar", "/app/tiering-kv.jar"]
