# Real Credentials Validation v7

## 设计

真实凭据网络验证 v7（ADR-0260）在 v6 延迟握手基础上扩展抖动矩阵：

```text
可达性 → 认证 → 权限 → 配额 → 延迟 → 抖动
    ↓
六项全过 → NetworkProbeResult.ok()=true
任一项失败 → degraded + 失败登记（ProbeFailure）
```

## 降级策略

- 端点未配置 / 凭据缺失：自动降级为模拟模式并在 `failures()`
  登记；
- 延迟或抖动超限：登记失败并返回 degraded 结果，不阻塞业务；
- 真实签名/权限校验由 Runner 注入实现，JVM 侧用确定性 fake 验证
  矩阵逻辑。

## 接入点

`io.tieringkv.config.CredentialProbe#probeNetworkV7`，测试见
`CredentialProbeV7Test`（延迟矩阵 / 抖动矩阵 / 降级登记）。
