# SQL Query Layer 路线图（探索原型）

Phase 27 · ADR-0113

## 当前原型

- `SELECT * FROM kv`（全表扫描）
- `WHERE key = 'x'`（点查，MVCC Snapshot Read）
- `WHERE key >= 'a' [AND key < 'b']`（范围查）
- `LIMIT n`

基准（进程内）：点查 0.36–0.5M ops/s、范围 1K 行 ≈5ms。

## 路线图

| 版本 | 能力 |
| --- | --- |
| v1.2 | JOIN（两表 hash join）、聚合（COUNT/SUM） |
| v1.3 | 索引下推、谓词下推、优化器 |
| v2.0 | 完整 SQL（DDL/DML 子集）、分布式执行 |

## 边界

只读原型，不宣称生产 SQL 引擎；JOIN/聚合为后续版本。
