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
# tc netem 混沌注入（ADR-0343）：真实网络故障演练需要 iproute2；默认以非 root（10001:10001）运行。
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r -g 10001 tieringkv \
    && useradd -r -u 10001 -g 10001 tieringkv \
    && mkdir -p /app/data/wal /app/config /data \
    && chown -R 10001:10001 /app /data
COPY --from=build --chown=10001:10001 /tmp/tiering-kv.jar /app/tiering-kv.jar
USER 10001:10001
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Dio.netty.allocator.type=pooled -Xmx1g"
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD /bin/bash -c 'if (exec 3<>/dev/tcp/127.0.0.1/9100) 2>/dev/null; then exec 3>&-; curl -fsS http://127.0.0.1:9100/healthz >/dev/null; exit $?; fi; for p in 6379 7000 7100 7200 7201 7300; do if (exec 3<>/dev/tcp/127.0.0.1/$p) 2>/dev/null; then exec 3>&-; exit 0; fi; done; exit 1'
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/tiering-kv.jar \"$@\"", "--"]
