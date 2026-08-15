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

## BIT 命令族（ADR-0334，Phase 66）

- SETBIT/GETBIT：位图即字符串（大端字节序，位位置 = 7-offset%8），
  SETBIT 零字节扩展并保留 TTL（AtomicStringOps 原子更新）；
- BITCOUNT/BITPOS：支持 `[start end [BYTE|BIT]]` 范围、负索引；
  BITPOS 缺失键：bit=0 → 0、bit=1 → -1；显式范围未命中 → -1；
- BITOP AND/OR/XOR/NOT：缺失源按零串，结果长度为最长源；
  NOT 仅单源；结果全零仍写入目标（Redis 语义）。

## GEO 命令族（ADR-0335，Phase 66）

- GEOADD/GEOPOS/GEODIST/GEOHASH/GEOSEARCH/GEORADIUS/
  GEORADIUSBYMEMBER；
- 存储复用 ZSET：score = 52 位 geohash（lat 偶位/lon 奇位，
  lat ∈ [-85.05112878, 85.05112878]），TYPE=zset、ZRANGE/ZSCORE
  兼容；GEOHASH 字符串按 Redis 口径（±90 重编码 + 第 11 位 '0'）；
- 距离：WGS-84 二次平均半径 6372797.560856m haversine；
- 检索：精确过滤（O(N)），GEOSEARCH 支持 FROMMEMBER/FROMLONLAT ×
  BYRADIUS/BYBOX + ASC/DESC + COUNT + WITHCOORD/WITHDIST/WITHHASH；
  STORE/STOREDIST/GEOSEARCHSTORE 暂缓（P2 已知差异）。

## JSON 路径命令族（ADR-0336，Phase 67）

- JSON.SET/GET/DEL/TYPE/ARRAPPEND/ARRLEN/OBJKEYS/OBJLEN/STRLEN/
  NUMINCRBY（扩展注册表，默认注册表不包含）；
- 解析/序列化由 jackson-databind 2.18.2 负责（ADR-0336）；
- 路径子集：`$`、`.field`、`['field']`、`[n]`（负索引）、`.*`/`[*]`、
  `..field`/`..*`；SET/DEL/NUMINCRBY/ARRAPPEND 仅支持根与简单字段/
  索引链（通配/递归变更暂缓，已知差异）；
- JSON.SET 支持 NX/XX（键级）与缺失中间对象按字段创建；JSON.GET
  单路径返回序列化值（JSONPath 返回匹配数组文本）、多路径返回对象；
- 变更命令经 TypeSupport.update 原子执行并保留 TTL。
