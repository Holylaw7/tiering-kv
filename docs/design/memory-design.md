# 内存层详细设计（Memory Design）

状态：✅ 已实现（Phase 2，ADR-0007 / 0008 / 0009）

## 1. 架构

```text
Command Layer
     │
     ▼
StorageEngine（SPI）
     │
     ▼
MemTable（64 段 SkipList + 分段读写锁）
     ├── KeyValueEntry（版本 / tombstone / TTL / size）
     ├── MemoryManager（used/max + 淘汰回调接口）
     └── TTLManager（惰性 + min-heap 主动清扫）
```

## 2. 接口

```java
public interface StorageEngine {
    void put(byte[] key, byte[] value);
    void put(byte[] key, byte[] value, long ttlMillis);
    byte[] get(byte[] key);
    boolean delete(byte[] key);
    boolean exists(byte[] key);
    StorageIterator iterator();
    long size();
}
```

Command 层只依赖 `StorageEngine`，禁止 `new MemTable()` 出现在命令层。

## 3. KeyValueEntry

| 字段 | 语义 |
| --- | --- |
| key / value | 二进制安全；value 在 tombstone 时为 null |
| createTimestamp / updateTimestamp | 创建与最后修改时间（ms） |
| expireTimestamp | -1 永不过期；>=0 为过期时间点 |
| version | 全局单调版本号（Version），供 TTL 守卫与 WAL 排序 |
| deleted | tombstone 标记 |
| size | key + value + 固定开销（估算字节） |

## 4. 并发（ADR-0008）

- 64 段；`fnv1a(key) & 63` 定位段；
- 读锁：GET / EXISTS / 迭代采集；写锁：PUT / DELETE / 主动过期；
- 内存压力回调在锁外触发；无全局锁。

## 5. TTL（ADR-0009）

- 惰性：访问时 `now >= expireTimestamp` 视为不存在；
- 主动：min-heap（expireMillis, version, segment, key），后台 daemon 线程
  每秒清扫；版本守卫防误删；
- `SET key value EX seconds | PX milliseconds`。

## 6. 删除语义

- `DEL` → tombstone（保留键位，为 WAL/Snapshot/LSM 准备）；
- TTL 主动过期 → 物理移除（内存回收）；
- 惰性过期键在 `size()` 中仍计数，直到主动清扫（与 Redis 语义一致）。

## 7. 迭代器

- 逐段读锁采集存活 entry → PriorityQueue 多路归并 → 全局有序；
- 弱一致快照（非强一致），Phase 6 如需强一致另行设计。

## 8. 已知限制

- 迭代器为分段快照，跨段时刻不完全一致；
- 淘汰回调仅接口（Phase 3 实现 LFU/ARC）；
- tombstone 尚未压缩回收（Phase 4/5 compaction）。
