# ADR-0358：控制台逻辑备份格式（JSONL）

## Status

Accepted（2026-10-23）

## Context

内核提供 `BackupManager`（面向 SSTable/WAL 的物理备份），但未暴露为
RESP 命令，控制面无法触发；而运维平台需要一个"看得见、可下载、可回放"
的备份能力。

## Decision

控制面实现**逻辑备份**：`SCAN` 全量遍历 → 按类型取值 → 逐行写 JSONL。

每行结构：

```json
{"key":"user:1","type":"hash","ttl":-1,"value":{"name":"张三"}}
```

- `type`：string/hash/list/set/zset/json（stream/timeseries 暂不备份）；
- `ttl`：剩余秒数，-1 表示永久，恢复时以 `EXPIRE` 还原；
- 恢复时先 `DEL` 再按类型重建，保证幂等；
- 单次备份键数上限 20 万，超过则截断并记录日志。

## Alternatives

- 暴露内核 `BackupManager` 为 `BACKUP` 命令：需要改动冻结的命令表（157 项）
  与协议文档，且物理备份无法跨版本恢复，否决（当前阶段）；
- RDB 兼容格式：需要实现完整的 RDB 编解码，成本远高于收益，否决；
- `mysqldump` 式流式导出：语义不符（本系统不是关系库），否决。

## Consequences

- 备份文件可读、可 diff、可直接下载，便于演示与排障；
- 大键空间下备份为 O(keys) 次往返（每键 3 次：TYPE/TTL/取值），
  已在文档中标注适用范围（十万级键）；
- 与内核物理备份并存：物理备份面向灾难恢复，逻辑备份面向迁移与演示。
