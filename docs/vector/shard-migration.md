# 向量分片迁移

Phase 30 · ADR-0127

## 能力

- `ShardMigrationExecutor`：逐 id 迁移（源删 + 目标写）；
- `verify()`：源清空校验；
- 迁移后查询召回保持（目标分片直接检索）。

## 使用

```java
ShardMigrationExecutor executor =
        new ShardMigrationExecutor(source, target);
executor.migrate(id, embedding);
assertThat(executor.verify()).isTrue();
```

## 限制

- 双写窗口由调用方管理（Phase 31 与 ShardRouter 联动）；
- 增量 CDC 更新待 Phase 31。
