# ADR-0331: JDK 21 Virtual Threads POC

## Status

Accepted

## Context

TD-002：JDK 17 目标下暂不采用虚拟线程。项目运行时入口（GatewayRuntime/
连接处理）以线程池为主；JDK 21 虚拟线程可降低阻塞型连接成本。

## Decision

- 构建/运行目标维持 JDK 17（协议与依赖兼容），新增虚拟线程 POC：
  `GatewayRuntime` 连接处理可选 VT 模式（系统属性开关）；
- POC 基准：固定线程池 vs 虚拟线程（连接数 1k/10k，延迟/吞吐/JFR）；
- 结果记录 docs/benchmark/phase64-virtual-threads-report.md；
- 不改变默认行为（默认平台线程），POC 通过后评估正式升级。

## Alternatives

1. 直接升级 JDK 21：依赖/构建矩阵风险；
2. 不评估：保持线程池。

## Consequences

优点：阻塞连接成本降低，连接规模提升。

缺点：JDK 21 运行时依赖（POC 阶段不影响构建目标）。

风险：虚拟线程与现有池化/NIO 混合的线程语义需验证。

## Implementation

GatewayRuntime VT 模式 + 基准 + 报告（POC 性质）。
