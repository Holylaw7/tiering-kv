# SLA/SLO 管理指南（ADR-0162）

## 定义

```java
manager.define(new SloDefinition("latency-slo", "latency_p99",
        0.95, 100)); // 指标 + 目标达成率 + 窗口
manager.record("latency-slo", success);
```

## 状态

- COMPLIANT：compliance ≥ target；
- AT_RISK：target - 0.10 ≤ compliance < target；
- BREACHED：compliance < target - 0.10。

## 告警

`SloAlert.evaluate(manager, sloIds)` 对 AT_RISK / BREACHED 输出告警。
