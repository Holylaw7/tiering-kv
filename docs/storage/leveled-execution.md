# Leveled Compaction 执行指南（ADR-0207）

## 使用

```java
LeveledCompactionExecutor executor = new LeveledCompactionExecutor();
ExecutionResult result = executor.execute(plan, entries, now);
Map<String, Long> summary = executor.summarize(entries, now);
```

## 语义

- latest wins（同 key 取最后）；
- tombstone 移除 key；TTL 过期清理；
- 输出按层级计划落盘，SSTable 格式兼容（零回退）。
