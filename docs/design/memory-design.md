# 内存层详细设计（Memory Design）

状态：草稿（Phase 2 细化）

## 范围

MemTable（分段哈希 + 跳表）、TTL、内存配额。

## 接口草案

```java
// 草案
public interface MemTable {
  byte[] get(byte[] key);
  boolean put(byte[] key, byte[] value, long ttlMillis);
  boolean delete(byte[] key);
  Iterator<Entry> scan();
}
```

## 待定项

- 分片数量与锁策略（ADR-0003：64 分片起步）；
- 跳表用于有序集合/范围扫描的时机；
- TTL 惰性删除 vs 定期清理。
