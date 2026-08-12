# ADR-0194: Object Storage Cold Tier Archive

## Status

Accepted

## Context

COLD 层物化视图仍占用本地/远端存储；需要归档到对象存储（S3 兼容）
释放空间。

## Decision

1. `datamesh/ObjectStorageArchive`：冷层视图 → 对象存储（模拟 S3）
   上传/下载/删除；
2. 归档保持 stale 语义 + 主权约束；
3. 与 AutoTierManager / MaterializedViewLifecycle 联动；
4. 验收：归档矩阵 + 恢复 + 主权拒绝。

## Alternatives

1. 本地保留：存储成本高；
2. 直接删除：不可恢复。

## Consequences

优点：冷数据低成本保留。

缺点：恢复延迟高。

风险：归档损坏由校验与回退兜底。

## Implementation

代码影响范围：`datamesh/` + 测试 +
`docs/datamesh/object-storage-archive.md`。
