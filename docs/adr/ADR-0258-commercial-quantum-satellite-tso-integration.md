# ADR-0258: Commercial Quantum/Satellite TSO Integration

## Status

Accepted

## Context

Phase 48 量子/卫星授时（ADR-0251）为硬件接口 + 模拟实现。Phase 49 升级
为商用设备连接器：多厂商驱动 SPI、连接生命周期、健康检查、主备故障
切换、单调防回拨，并接入跨云授时仲裁。

## Decision

采用 `CommercialTimeDeviceConnector`：

- 设备 SPI（connect / readTimeMillis / health / disconnect）+ 驱动注册；
- 商用设备未配置时模拟回退，降级登记；
- 单调推进 + 防回拨（接入 CrossCloudTsoArbitration）；
- 主备设备故障切换与恢复。

## Alternatives

1. 仅保留模拟实现：无法对接真实商用设备；
2. 强依赖单一厂商驱动：供应商锁定；
3. 无单调保护直读设备：存在时钟回拨风险。

## Consequences

优点：多厂商可插拔、可降级、单调性受保护。

缺点：真实设备链路仍需要硬件环境验证。

风险：商用设备协议差异大，驱动适配需持续演进。

## Implementation

`src/main/java/io/tieringkv/transaction/tso/CommercialTimeDeviceConnector.java`
+ `src/test/java/io/tieringkv/transaction/tso/CommercialTimeDeviceConnectorTest.java`、
`docs/transaction/commercial-time-device-integration.md`。
