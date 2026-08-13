# ADR-0299: Upgrade & Backup Drills

## Status

Accepted

## Context

滚动升级与备份恢复只有代码无演练产物。

## Decision

采用演练脚本 + 门控测试：

- `scripts/upgrade-drill.sh`：逐节点升级 + 追平等待 + 数据奇偶校验；
- `scripts/restore-drill.sh`：快照 + WAL + MVCC 索引恢复校验；
- 门控测试校验脚本存在、步骤完整、校验逻辑可执行。

## Alternatives

1. 文档描述：不可执行；
2. 全自动演练：依赖环境。

## Consequences

优点：演练可重复、校验闭环。

缺点：真实多机演练待 Runner。

风险：脚本与版本漂移需门控测试。

## Implementation

`scripts/upgrade-drill.sh`、`scripts/restore-drill.sh` +
`src/test/java/io/tieringkv/operations/DrillTest.java`、
`docs/operations/upgrade-backup-drills.md`。
