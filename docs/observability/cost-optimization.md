# Workload 成本优化指南（ADR-0160）

## 建议规则

| 画像特征 | 建议 | 收益估算 | 风险 |
| --- | --- | ---: | --- |
| 低活跃 + 有存储 | SCALE_DOWN | 30% | LOW/MEDIUM |
| 写密集 + 大存储 | COLD_TIER | 50% | MEDIUM |
| 大值对象 | COMPRESSION | 15% | LOW |

## 使用

```java
List<Suggestion> suggestions = optimizer.analyze(profile, cost);
List<Suggestion> all = optimizer.analyzeAll(profiles, costs);
```

建议必须输出收益/风险等级，不隐藏失败项。
