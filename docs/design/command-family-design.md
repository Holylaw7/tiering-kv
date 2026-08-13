# Command Family Design

## 架构

```text
CommandRegistry (38 commands)
    ↓
CommandEngine
    ↓
StorageEngine SPI
    ├── AtomicStringOps（MemTable 段锁原子实现）
    └── WALStorageEngine（WAL-first 同步委托）
```

## 原子语义

- INCR/DECR/INCRBY/DECRBY：段写锁内 read-modify-write，保留 TTL；
- APPEND：段锁内合并，保留 TTL；GETSET：清除 TTL（Redis 语义）；
- SETNX/GETDEL：段锁内 set-if-absent / get-and-delete；
- SETRANGE：保留 TTL + 零字节填充；
- WAL 装饰器对原子操作同步串行化，保证 WAL 值与实际值一致。

## TTL 语义

EXPIRE/PEXPIRE/EXPIREAT/PEXPIREAT → `AtomicStringOps.expireAt`
（绝对毫秒）；TTL/PTTL 返回 -2/-1/剩余；PERSIST 移除 TTL。
过期时间 <= now 立即删除。

## 管理命令

- SCAN：cursor 0 构建有序键快照，后续按偏移切片；
- CONFIG GET/SET：白名单（maxmemory/appendfsync/timeout/save/
  maxclients），GET 大小写不敏感；
- COMMAND COUNT/INFO：注册表元数据；
- CLIENT：SETNAME OK / GETNAME nil（无会话态限制）。
