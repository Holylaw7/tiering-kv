# 论文正文草稿（第三章、第四章）

基线：`4cc7a62`（Phase 11 之后）。所有图表、指标与结论均可追溯到该基线的源码、ADR 与测试报告。

| 文件 | 内容 | 引用图表 |
| --- | --- | --- |
| `chapter1-introduction.md` | 第一章　绪论（研究背景/现状评述/研究内容/组织结构） | 图 1-1 |
| `chapter2-technology-and-design.md` | 第二章　相关技术与系统总体设计（需求/核心技术/存储/分层/分布式/总体架构） | 图 2-1、表 2-1、表 2-2 |
| `chapter3-storage-engine.md` | 第三章　系统核心存储引擎设计与实现（WAL / MemTable / SSTable / Compaction / 冷热分层 / BlockCache） | 图 3-1、图 3-2 |
| `chapter4-control-plane.md` | 第四章　系统控制与管理模块设计与实现（RESP/Netty / INFO 可观测性 / RBAC 与审计 / 备份恢复） | 图 4-1、图 4-2 |
| `chapter5-distributed.md` | 第五章　分布式架构与一致性机制设计（分片 / Raft / 故障恢复 / HLC / 两阶段事务） | 图 5-1、图 5-2、图 5-3 |
| `chapter6-testing-results.md` | 第六章　系统测试与实验结果分析（策略/环境/功能/E2E/故障/性能/质量/缺陷案例/局限） | 图 6-1 |
| `formatting-conventions.md` | 图表编号与交叉引用规范（章节编号、新旧对照） | — |
| `chapter7-conclusion.md` | 第七章　总结与展望（工作总结 / 主要成果 / 局限 / 未来工作 / 小结） | — |
| `consistency-review.md` | 第三至六章数据/术语/结论强度的一致性核对 | — |
| `chapter7-review.md` | 第七章复核记录（篇幅、成果对应、局限与未来工作溯源、机械检查） | — |
| `terminology.md` | 技术术语统一表（中文名/英文写法/边界表述/单位格式） | — |
| `chapter1-reference-map.md` | 第一章文献筛选与映射表（15 篇核心 + 4 项规范，定稿版） | — |

图表源文件在 `docs/design/figures/`：
图 1–3 为直接生成的 SVG/PNG，图 4–6 为 Mermaid（`.mmd`）+ 渲染结果。

写作约定：

- 每个模块按"设计目标 → 方案与选型 → 实现机制 → 图 → 实验 → 局限"展开；
- 引用文献只列与实际实现对应的条目（LSM-Tree、Bigtable、Bloom Filter、Raft、2PC、ARC 评估），
  未采用的方案在正文与引文中明确标注"评估后未采用"；
- 所有实验数字都带口径，口径出处见 `docs/testing/phase10-e2e.md` 与 `docs/benchmark/*`。