# ADR-0328: Hot Cache Version Check

## Status

Accepted

## Context

TD-018：HotKeyReadCache 以 TTL 兜底（默认 500ms），TTL 窗口内写失效
依赖 invalidate 事件；绕过装饰器的写或时钟偏差可能读到陈旧值。

## Decision

- `StorageEngine` 增加 `default long versionOf(byte[] key) { return 0; }`；
  MemTable 覆盖返回键版本；
- HotKeyReadCache 的 CachedValue 记录 value + expireAt + version；
  get 时：缓存存在且（version 新鲜 = `cached.version() >=
  storage.versionOf(key)`）→ 直接返回（TTL 仅作兜底，版本一致即
  新鲜）；version 变化或缺失 → reload；
- 无版本存储（versionOf 恒 0）回退现有 TTL 语义；
- 写失效（put/delete invalidate）保留。

## Alternatives

1. 仅 TTL：陈旧窗口不可消除；
2. 全局版本号：同 key 粒度太粗。

## Consequences

优点：热点读强新鲜（版本一致即返回），消除 500ms 陈旧窗口。

缺点：每次热读多一次 versionOf 查询（段读锁，成本低）。

风险：默认 versionOf 恒 0 → 必须与 TTL 兜底配合，避免误判新鲜。

## Implementation

`storage/StorageEngine.java`（+default versionOf）、
`storage/memory/MemTable.java`（override）、
`concurrency/hotkey/HotKeyReadCache.java` + 测试。
