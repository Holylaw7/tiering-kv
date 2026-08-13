# Product Completeness Baseline

> 基线版本：3.2.0 GA（Phase 50，ADR-0268）。

## 能力分层

| 能力 | 分层 | 状态 |
| --- | --- | --- |
| RESP 核心命令 | PRODUCT | green |
| 全量 Redis 命令族 | EXPERIMENTAL | partial |
| LSM 冷热分层 | PRODUCT | green |
| WAL 持久化/恢复 | PRODUCT | green |
| Multi-Raft 复制 | PRODUCT | green |
| MVCC + Percolator 2PC | PRODUCT | green |
| SQL 引擎 | EXPERIMENTAL | prototype |
| Vector/HNSW | EXPERIMENTAL | prototype |
| 控制台/SaaS UI | EXPERIMENTAL | prototype |
| 联邦学习下推 | EXPERIMENTAL | decision layer |
| 量子/卫星授时 | ADAPTER | SPI + 模拟回退 |
| S3 / Spot | ADAPTER | 真实端点 SPI + fallback |

## 技术债终态

每项债务唯一终态：CLOSED / ACCEPTED_LIMITATION /
ENV_BLOCKED_FINAL（详细表见 `ProductCompletenessBaseline.techDebts()`）。

## 成品判定清单

1. 版本模型一致（pom/tag/notes/changelog）；
2. 全量回归 0 failures；
3. 每项门禁唯一终态；
4. 基准可复现（固定参数）；
5. 文档可独立上手；
6. 无门禁滚动 defer。

当前判定：**PASS**（`ProductCompletenessBaseline.passes()`）。
