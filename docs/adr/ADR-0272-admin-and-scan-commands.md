# ADR-0272: Admin & Scan Commands

## Status

Accepted

## Context

缺少 DBSIZE/FLUSHDB/SCAN/TYPE/CONFIG/CLIENT/COMMAND 管理命令，
运维与调试只能靠 INFO。

## Decision

采用快照游标 SCAN 与白名单 CONFIG：

- DBSIZE = storage.size()；FLUSHDB/FLUSHALL = storage.clear()；
- SCAN：cursor 0 构建有序键快照，后续游标按索引切片返回
  （MATCH 支持 * 与 ?，COUNT 默认 10）；
- CONFIG GET/SET：白名单（maxmemory/appendfsync/timeout/save/
  maxclients），未知参数报 ERR；
- CLIENT SETNAME 返回 OK / GETNAME 返回 nil（无会话态，文档登记）；
- COMMAND COUNT/INFO：注册表命令元数据。

## Alternatives

1. KEYS 全量返回：大键空间阻塞；
2. CONFIG SET 任意项：安全风险；
3. 无 SCAN：无法增量遍历。

## Consequences

优点：遍历安全、配置受限、命令元数据可查询。

缺点：SCAN 快照在游标生命周期内持有引用。

风险：CLIENT 无会话态为已知限制，需文档标注。

## Implementation

`command/` 管理命令族 + `src/test/java/io/tieringkv/command/AdminCommandTest.java`。
