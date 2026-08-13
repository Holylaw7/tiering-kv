# ADR-0244: Global TSO Unified Clock (Quantum/Satellite)

## Status

Accepted

## Context

Phase 46 的 `CrossCloudTsoArbitration` 基于软件时间源。Phase 47 提供
量子/卫星授时源原型抽象与校准。

## Decision

新增 `QuantumSatelliteTimeSource`：

- 授时源类型：QUANTUM / SATELLITE / HYBRID；
- 校准：源时间 + 传播延迟估计 → 修正时间；
- 单调 + 防回拨复用 CrossCloudTsoArbitration 语义；
- 与 CrossCloudTsoArbitration / GlobalTsoClock / resolved-ts /
  事务协调器联动。

## Alternatives

1. 纯软件时间源：漂移不可控；
2. 硬件直连：不可移植；
3. 原型抽象 + 模拟：可测试、可演进，选中。

## Consequences

优点：跨云可比较时间戳 + 原型可接入硬件。

缺点：原型为模拟，真实接入需硬件。

风险：传播延迟估计误差 → 校准上限约束。

## Implementation

`transaction/tso/QuantumSatelliteTimeSource` +
`src/test/java/io/tieringkv/transaction/tso/QuantumSatelliteTimeSourceTest`、
`docs/transaction/quantum-satellite-tso-clock.md`。
