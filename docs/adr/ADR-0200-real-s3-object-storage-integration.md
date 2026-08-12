# ADR-0200: Real S3 Object Storage Integration

## Status

Accepted

## Context

Phase 40 的对象存储归档为进程内模拟（TD-073）；需要真实 S3 兼容 API
接入，并保留模拟 fallback。

## Decision

1. `datamesh/S3ObjectStorage`：S3 兼容客户端抽象（bucket/key/put/get/
   delete + 模拟 fallback）；
2. 与 ObjectStorageArchive 联动（可配置降级）；
3. 验收：S3 语义矩阵 + fallback 切换 + 主权约束保留。

## Alternatives

1. 仅模拟：无法真实部署；
2. 强制真实 S3：无 S3 环境不可测试。

## Consequences

优点：真实可部署 + 本地可测。

缺点：需要 S3 凭据/端点配置。

风险：接入失败由 fallback 兜底。

## Implementation

代码影响范围：`datamesh/` + 测试 +
`docs/datamesh/s3-integration.md`。
