# ADR-0263: Structured Logging & Secret Redaction

## Status

Accepted

## Context

全仓库没有日志框架（0 个 slf4j/log4j 使用点），排查与审计只能依赖
System.out。生产系统必须具备分级、可配置、可轮转且不泄露凭据的日志。

## Decision

采用 slf4j-api + logback-classic：

- `logback.xml`：控制台 + RollingFileAppender（./logs/tiering-kv.log），
  级别可配置；
- `OpsLogger`：关键路径命名日志（startup/shutdown/wal/migration/
  raft/txn/credential）；
- `Redactor`：凭据/密钥/token/连接串密码统一脱敏，任何日志输出前
  必须过 Redactor；
- 禁止新增 System.out 记录业务日志（基准 stdout 除外）。

## Alternatives

1. 继续 System.out：无级别、无轮转、不可审计；
2. java.util.logging：配置弱、生态差；
3. Log4j2：功能强但依赖面更大，logback 已足够。

## Consequences

优点：分级可配置、滚动归档、敏感信息不落盘。

缺点：日志框架引入额外依赖与配置负担。

风险：脱敏规则遗漏会泄露凭据，需要测试矩阵持续覆盖。

## Implementation

`pom.xml`、`src/main/resources/logback.xml`、
`io.tieringkv.observability.{Redactor,OpsLogger}`、
`src/test/java/io/tieringkv/observability/LoggingRedactionTest.java`、
`docs/operations/logging-guideline.md`。
