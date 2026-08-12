# 商业化运营指标指南（ADR-0155）

## 指标

- MRR：`MrrCalculator.mrr(activeTenants)` / `record(invoice)`；
- 试用转化：`TrialConversionTracker.conversionRate()` =
  转化 /（转化 + 过期）；
- 流失：`ChurnDetector.churnRate()` = 流失 /（流失 + 续费）；
- 告警：`CommercialAlert` 默认阈值（流失 5%、转化 30%、MRR 下跌 10%）。

## 使用

```java
List<Alert> alerts = new CommercialAlert().evaluate(
        churn, conversion, mrrNow, mrrBefore);
```

阈值可参数化验收，口径变化由测试矩阵兜底。
