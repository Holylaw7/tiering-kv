# CDC 多消费者组

Phase 27 · ADR-0112

## 1. 模型

```text
CdcLog（单份事件流）
  ├─ ConsumerGroup("warehouse") → 独立 checkpoint
  ├─ ConsumerGroup("search")    → 独立 checkpoint
  └─ CDCConsumerRegistry（注册/列表/删除）
```

单事件多组投递，组间进度互不影响；exactly-once 按组保持。

## 2. 恢复

组崩溃后从自身 checkpoint 恢复，不影响其他组。

## 3. 限制

- 多组消费增加读放大（内存可缓存段，Phase 28 优化）；
- 删除组后 checkpoint 保留（防重复投递误解）。
