# 对象生命周期联动指南（ADR-0203）

## 使用

```java
ObjectLifecycleManager lifecycle = new ObjectLifecycleManager();
lifecycle.addRule(new LifecycleRule("obj-", 30));
boolean applied = lifecycle.apply(object, now);
boolean expired = lifecycle.expired(object, now);
lifecycle.protect("obj-v1"); // 恢复保护
```

TTL 匹配 → 生成过期策略；受保护对象不参与过期清理。
