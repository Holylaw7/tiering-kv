# 论文正文草稿（第三章、第四章）

基线：`4cc7a62`（Phase 11 之后）。所有图表、指标与结论均可追溯到该基线的源码、ADR 与测试报告。

| 文件 | 内容 | 引用图表 |
| --- | --- | --- |
| `chapter3-storage-engine.md` | 第三章　系统核心存储引擎设计与实现（WAL / MemTable / SSTable / Compaction / 冷热分层 / BlockCache） | 图 2、图 4 |
| `chapter4-control-plane.md` | 第四章　系统控制与管理模块设计与实现（RESP/Netty / INFO 可观测性 / RBAC 与审计 / 备份恢复） | 图 1、图 5、图 6 |

图表源文件在 `docs/design/figures/`：
图 1–3 为直接生成的 SVG/PNG，图 4–6 为 Mermaid（`.mmd`）+ 渲染结果。

写作约定：

- 每个模块按"设计目标 → 方案与选型 → 实现机制 → 图 → 实验 → 局限"展开；
- 引用文献只列与实际实现对应的条目（LSM-Tree、Bigtable、Bloom Filter、Raft、2PC、ARC 评估），
  未采用的方案在正文与引文中明确标注"评估后未采用"；
- 所有实验数字都带口径，口径出处见 `docs/testing/phase10-e2e.md` 与 `docs/benchmark/*`。
