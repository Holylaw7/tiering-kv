# ADR-0320: v4 M2 Multi-Model Encoding

## Status

Accepted

## Context

v4.0 M2（docs/planning/v4-roadmap.md）：把 SQL/JSON/时序/向量作为一等
值类型接入存储。现状：

- `TypedValueCodec`（ADR-0276）：TK 魔数 + 类型字节 + payload，
  类型字节 1–5（HASH/LIST/SET/ZSET/STREAM），STRING 裸字节；
- WAL / SSTable / 迁移 / 复制对值字节透明（冻结格式不变）；
- SQL/向量已有独立原型，但未作为 KV 类型化值接入；
- RESP3 已有 Map/Set/Double/Push 类型（ADR-0281）。

## Decision

### 1. 类型扩展（additive）

`ValueType` 增加 `JSON`、`TIME_SERIES`、`VECTOR`，类型字节 6/7/8；
既有 1–5 字节与语义完全不变（冻结）。`TypedValueCodec` 同步支持
（switch 穷举）。

### 2. payload 编码（MultiModelCodec）

- `JSON`：UTF-8 字节原样（校验最小合法性：平衡括号不做，留给解析层）；
- `TIME_SERIES`：二进制紧凑格式 `count u32 + (ts i64 + value f64)[]`；
- `VECTOR`：`dim u32 + float[dim]`（与 M1 向量索引文件维度语义一致）。

编码/解码失败明确抛异常，不静默截断；类型字节未知拒绝。

### 3. RESP3 接线（M2 首批）

- JSON → RespBulkString（UTF-8 原样）；
- TIME_SERIES → RespArray（元素为 [ts, value] 的 RespArray）；
- VECTOR → RespArray（float 以 RespDouble 呈现，维度与 M1 对齐）。

### 4. 兼容性

- WAL/SSTable/RPC 冻结格式不变：值仍是 `TK + type + payload` 字节，
  旧版本读到未知类型字节按"不支持"拒绝，不会误解析；
- TTL / 过期 / 冷热迁移 / 复制对类型化值透明（值字节不解释）；
- M1 向量索引（VectorIndexStore）与 M2 VECTOR 值并存：前者是检索
  索引，后者是 KV 值；接线（索引随值写入自动更新）列入 M2 收尾。

## Alternatives

1. 全 JSON 存储：时序/向量体积膨胀且无类型语义；
2. 独立类型系统不并入 TypedValueCodec：两套魔数增加解析分叉；
3. Protobuf：引入序列化依赖，冻结格式不可加。

## Consequences

优点：

- 多模型值可存可迁移可复制，RESP3 可表达；
- 全部 additive，v1.0–v3.7 冻结字节不变。

缺点：

- 类型字节空间有限（扩展需版本化，M3 前够用）；
- JSON 合法性由上层解析器负责，存储层不校验。

风险：

- 未知类型字节处理需一致（读路径全部拒绝）；
- VECTOR 值 + 索引双写一致性（M2 收尾接线时引入事务/补偿）。

## Implementation

```text
src/main/java/io/tieringkv/storage/types/ValueType.java      （+3 枚举）
src/main/java/io/tieringkv/storage/types/TypedValueCodec.java（+3 类型字节）
src/main/java/io/tieringkv/storage/types/MultiModelCodec.java（新）
src/test/java/io/tieringkv/storage/types/MultiModelCodecTest.java
```

测试：roundtrip / 类型字节兼容（1–5 不变）/ 损坏拒绝 / RESP3 映射 /
JSON 与向量维度校验。基准：多模型值编码吞吐（M2 报告）。

关联：docs/planning/v4-roadmap.md（M2 状态）、
.codex/tasks/phase59-v4-m2-multi-model-encoding.md。
