# ADR-0297: Linearizability Verification

## Status

Accepted

## Context

分布式正确性只有混沌/故障测试，缺少可复现的线性化历史验证。

## Decision

采用 `LinearizabilityChecker`：

- 操作历史（invoke/response + 时间戳 + 类型 GET/PUT）；
- 单键线性化点搜索：验证存在与实时序一致的串行化顺序；
- 并发写读矩阵生成可复现历史；违例历史必须被拒绝；
- 验证器独立于存储实现（纯历史输入）。

## Alternatives

1. 外部 Jepsen：依赖 Clojure 环境，当前不可执行；
2. 只做冒烟：无法证明线性化；
3. 模型检查全系统：范围过大。

## Consequences

优点：可复现、可拒绝违例、独立可测。

缺点：单键范围；跨键线性化留后续。

风险：验证算法复杂度需控制（小历史矩阵）。

## Implementation

`io.tieringkv.distributed.LinearizabilityChecker` +
`src/test/java/io/tieringkv/distributed/LinearizabilityTest.java`、
`docs/distributed/linearizability-verification.md`。
