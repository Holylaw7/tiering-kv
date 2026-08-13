# ADR-0251: Quantum/Satellite TSO Hardware Interface

## Status

Accepted

## Context

Phase 47 的 `QuantumSatelliteTimeSource` 是模拟原型。Phase 48 提供
硬件适配接口，模拟实现可替换为真实设备。

## Decision

新增 `QuantumSatelliteHardwareAdapter`：

- 硬件适配接口：readTime() / calibrate() / health()；
- 模拟实现：确定性模拟 + 可注入漂移/故障；
- 校准 + 单调 + 防回拨复用 QuantumSatelliteTimeSource 语义；
- 与 QuantumSatelliteTimeSource / CrossCloudTsoArbitration /
  resolved-ts / 事务协调器联动。

## Alternatives

1. 仅模拟：无法接硬件；
2. 硬件直连：不可移植；
3. 适配接口 + 模拟实现：可测试、可演进，选中。

## Consequences

优点：硬件可插拔；模拟可注入故障。

缺点：真实设备驱动需厂商 SDK。

风险：硬件故障 → 健康检查 + 降级兜底。

## Implementation

`transaction/tso/QuantumSatelliteHardwareAdapter` +
`src/test/java/io/tieringkv/transaction/tso/QuantumSatelliteHardwareAdapterTest`、
`docs/transaction/quantum-satellite-hardware-interface.md`。
