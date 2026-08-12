# 远端状态持久化指南（ADR-0179）

## 使用

```java
RemoteStateStore store = new RemoteStateStore(dir);
store.save("v1", snapshot, keys);
Optional<PersistedState> state = store.load("v1");
store.delete("v1");
```

## 语义

- 格式：MAGIC + payload + CRC32C；
- 缺失/损坏 → empty（调用方回退全量刷新）；
- 覆盖写、多视图、重开读取均支持；
- 恢复后增量语义不丢失。
