# ADR-0277: Hash Command Family

## Status

Accepted

## Context

Hash 是最常用的复合类型，缺少 HSET/HGET/HINCRBY 等基础命令。

## Decision

采用字段映射 + 插入序 + 段锁原子：

- HSET/HGET/HDEL/HEXISTS/HLEN/HKEYS/HVALS/HGETALL/HMGET/HMSET/
  HINCRBY/HSETNX；
- 字段序与 Redis 一致（插入序）；HINCRBY 段锁内原子；
- 非 hash 键 WRONGTYPE；空 hash 不自动删键（Redis 语义保留空结构）。

## Alternatives

1. 字段拆键：TTL/事务语义破坏；
2. 命令层 get+put：并发丢更新；
3. 只做 HGET/HSET：命令族不完整。

## Consequences

优点：命令完整、原子、语义对齐。

缺点：整值重写 O(n)。

风险：HINCRBY 溢出需 ERR 处理。

## Implementation

`io.tieringkv.command.HashCommand` +
`src/test/java/io/tieringkv/command/HashCommandFamilyTest.java`。
