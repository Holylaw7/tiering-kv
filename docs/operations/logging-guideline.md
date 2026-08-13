# Logging Guideline

## 框架

slf4j-api + logback-classic（ADR-0263）；配置见
`src/main/resources/logback.xml`：

- 控制台 + 滚动文件（./logs/tiering-kv.log，保留 7 天）；
- 级别通过 `LOG_LEVEL` 环境变量配置（默认 INFO）。

## 关键路径

`OpsLogger` 提供命名日志：

| Logger | 场景 |
| --- | --- |
| tieringkv.ops | startup / shutdown / warn / error |
| tieringkv.ops.wal | WAL flush / checkpoint |
| tieringkv.ops.migration | 冷热迁移 |
| tieringkv.ops.raft | 选举 / 复制 |
| tieringkv.ops.txn | 事务 commit / abort |
| tieringkv.ops.credential | 凭据探测 |

## 脱敏

所有字符串参数必须先过 `Redactor.mask(...)`；禁止输出：

- password / passwd / pwd / secret / token / api_key / access_key
- Authorization 头
- URL 中的 user:password

脱敏规则由 `LoggingRedactionTest` 矩阵持续覆盖。
