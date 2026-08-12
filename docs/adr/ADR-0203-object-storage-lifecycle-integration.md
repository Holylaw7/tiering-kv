# ADR-0203: Object Storage Lifecycle Integration

## Status

Accepted

## Context

物化视图 TTL 与对象存储生命周期未联动；过期视图不会自动删除远端对象。

## Decision

1. `datamesh/ObjectLifecycleManager`：视图 TTL → 对象存储过期策略
   （模拟 S3 生命周期规则）；
2. 与 ObjectStorageArchive / MaterializedViewLifecycle 联动；
3. 验收：TTL → 规则生成 + 过期清理 + 恢复保护。

## Alternatives

1. 手动清理：易漏；
2. 无恢复保护：误删风险。

## Consequences

优点：过期自动清理，恢复可保护。

缺点：生命周期规则需维护。

风险：误删由恢复保护兜底。

## Implementation

代码影响范围：`datamesh/` + 测试 +
`docs/datamesh/object-lifecycle.md`。
