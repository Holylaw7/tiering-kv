# ADR-0270: TTL Command Family

## Status

Accepted

## Context

存储层有 TTL（TTLManager + 惰性/主动过期），但命令层没有
EXPIRE/TTL/PERSIST，TTL 能力无法被客户端使用。

## Decision

命令层补齐 TTL 全族，复用现有 TTLManager：

- EXPIRE/PEXPIRE/EXPIREAT/PEXPIREAT/TTL/PTTL/PERSIST；
- TTL 剩余 = -2（不存在）/ -1（无 TTL）/ ≥0（剩余毫秒）；
- expire ≤ 当前时间 = 立即删除（Redis 语义）；
- TTL 变更经 AtomicStringOps.expireAt/persist 落到段锁 + WAL
  （WAL 以 PUT + 相对 TTL 记录表示）。

## Alternatives

1. 命令层自造过期扫描：绕过 TTLManager，双重维护；
2. 仅提供 EXPIRE 不提供 PERSIST/TTL：命令族不完整；
3. 修改 WAL 格式新增 EXPIRE 记录：冻结格式变更，不可接受。

## Consequences

优点：语义与 Redis 对齐、TTLManager 单一事实来源、格式冻结。

缺点：TTL 剩余值受时钟影响，测试需容忍毫秒级抖动。

风险：相对 TTL 落盘在长停机恢复后有偏差（与既有 WAL 语义一致）。

## Implementation

`command/` TTL 命令族 + `src/test/java/io/tieringkv/command/TtlCommandFamilyTest.java`。
