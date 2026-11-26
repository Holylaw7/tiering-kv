FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 构建期走阿里云镜像：本机网络直连 Maven Central 返回 403（CDN 限流）
COPY deploy/maven-settings.xml /tmp/maven-settings.xml
COPY pom.xml .
COPY tiering-protocol/pom.xml tiering-protocol/pom.xml
COPY tiering-storage/pom.xml tiering-storage/pom.xml
COPY tiering-server/pom.xml tiering-server/pom.xml
COPY tiering-cluster/pom.xml tiering-cluster/pom.xml
COPY tiering-operator/pom.xml tiering-operator/pom.xml
COPY console-server/pom.xml console-server/pom.xml
COPY tiering-protocol/src tiering-protocol/src
COPY tiering-storage/src tiering-storage/src
COPY tiering-server/src tiering-server/src
COPY tiering-cluster/src tiering-cluster/src
# 仅构建核心运行态模块（tiering-cluster + 下游依赖），不打包 tiering-operator（剔除 fabric8/kotlin/okhttp）
RUN --mount=type=cache,target=/root/.m2 \
    mvn -s /tmp/maven-settings.xml -pl tiering-cluster -am -q -DskipTests package
RUN set -e; \
    jar=$(ls tiering-cluster/target/tiering-cluster-*-shaded.jar 2>/dev/null | head -n1); \
    test -n "$jar" || jar=$(ls tiering-cluster/target/tiering-cluster-*.jar | grep -v shaded | grep -v tests | head -n1); \
    test -n "$jar"; \
    cp "$jar" /tmp/tiering-kv.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
# tc netem 混沌注入（ADR-0343）：真实网络故障演练需要 iproute2。
RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /tmp/tiering-kv.jar /app/tiering-kv.jar
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Dio.netty.allocator.type=pooled -Xmx1g"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/tiering-kv.jar \"$@\"", "--"]
