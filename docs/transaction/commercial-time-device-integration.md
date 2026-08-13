# Commercial Quantum/Satellite TSO Device Integration

## 设计

商用授时设备连接器（ADR-0258）提供多厂商驱动 SPI：

```text
TimeDevice (connect / readTimeMillis / healthy / disconnect)
    ↓
CommercialTimeDeviceConnector
    ├── 主设备读取（+ 传播延迟校正）
    ├── 主设备故障 → 备设备自动切换
    ├── 单调推进 + 防回拨
    └── CrossCloudTsoArbitration 联动（仲裁时间取 max）
```

## 关键行为

- 驱动注册：`registerDriver(TimeDevice)`，按 vendor 管理；
- 连接生命周期：`connect / disconnect`，状态通过 `status(vendor)`
  暴露；
- 故障切换：主设备不可用时自动连接备用设备并累计 `switchovers()`；
- 全部设备故障：降级返回上次读数并累计 `failures()`；
- 真实硬件未配置时使用 `SimulatedTimeDevice` 回退，并在文档/报告中
  明确标注为参考实现。

## 单调性保证

每次读取 `candidate = max(device + delay, lastTimestamp + 1)`；
接入仲裁后 `candidate = max(candidate, arbitration.timestamp())`，
确保跨云水位不回拨。

## 接入点

`io.tieringkv.transaction.tso.CommercialTimeDeviceConnector`，测试见
`CommercialTimeDeviceConnectorTest`（单调矩阵 / 切换矩阵 / 仲裁矩阵）。
