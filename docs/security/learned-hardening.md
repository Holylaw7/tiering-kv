# 学习型自适应加固指南（ADR-0197）

## 学习规则

```text
高风险反馈 → threshold -= step（更早加固）
低风险反馈 → threshold += step
上下界 [min, max] 钳制
```

## 使用

```java
LearnedHardener hardener = new LearnedHardener(50, 10, 90, 5);
hardener.learn(highRiskObserved);
hardener.audit(); // 阈值调整审计
```

阈值变化限幅 + 审计，可回滚到初始配置。
