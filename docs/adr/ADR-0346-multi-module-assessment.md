# ADR-0346: Multi-Module Split Assessment (TD-001)

## Status

Accepted

## Context

TD-001：单 Maven 模块；若模块耦合升高需评估拆分多模块。

现状（2026-08-16，源码级依赖扫描）：

- `protocol`：零依赖（RESP 编解码独立，理想底层边界）；
- 主链单向：`protocol → storage → command → network`，无反向环；
- `storage` 依赖 protocol（RESP3 值映射）、`command` 依赖
  storage/protocol、`network` 依赖 command/protocol/storage；
- 横向耦合较重：`cluster`（153 文件）依赖 command/mvcc/protocol/
  storage/security 等；`observability` 聚合器反向引用 cluster/
  replication/vector；`transaction` 依赖 cluster/mvcc/replication；
- 全仓 744 个主源文件、约 40 个功能包，单模块构建 ~6 分钟
  （含测试），Java 17/21 单一 toolchain。

## Decision

**保持单 Maven 模块（monolith-first），本阶段不拆分**，并固化关键
边界为可执行测试（PackageBoundaryTest）：

1. `protocol` 不得依赖任何 `io.tieringkv` 内部包；
2. `storage` 不得依赖 `command` / `network`；
3. `command` 不得依赖 `network`；
4. 主链 `protocol → storage → command → network` 无反向环；
5. 未来若拆分，建议模块边界与依赖顺序：
   `tiering-kv-protocol` → `tiering-kv-storage` →
   `tiering-kv-command` → `tiering-kv-network`（其余包按
   cluster/transaction/observability 收敛后另行评估）。

## Alternatives

1. 立即拆分 4 个核心模块：cluster/transaction/observability 的
   横向依赖会形成跨模块环，需先做 3-6 个月解耦重构，收益（独立
   构建/发布）与当前单模块 6 分钟构建不成比例；
2. 只拆 protocol 模块：收益有限，且当前无外部消费者；
3. 拆分后再评估：成本前置，违背渐进式工程原则。

## Consequences

优点：

- 决策有证据（依赖矩阵 + 边界测试防回归）；
- 保持单模块的简单构建/门禁/发布流程；
- 未来拆分路径已记录（模块顺序 + 需先收敛的耦合点）。

缺点：

- 单模块仍承担全量编译与测试（可接受，~6 分钟）；
- cluster/observability 横向耦合不消除（登记为已知结构债务）。

风险：

- 若后续新增跨层依赖，PackageBoundaryTest 会立即失败（这是
  期望行为，防止边界腐化）。

## Implementation

新增 `architecture/PackageBoundaryTest`（源码 import 扫描）：
protocol 零内部依赖、storage/command/network 边界、主链无环；
本 ADR 登记于 ROADMAP TD-001（关闭：保持单模块，评估证据存档）。
