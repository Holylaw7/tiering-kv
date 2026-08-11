# 跨云数据主权与合规

Phase 32 · ADR-0143

## 策略

```text
DataResidencyPolicy(region → residency)
ComplianceValidator.validate(from, to)：跨驻留边界拒绝
```

## 语义

- 同驻留允许；跨驻留抛 SecurityException；
- 未声明地域按 default；
- 默认拒绝（fail-closed）。
