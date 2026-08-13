# ADR-0279: Set Command Family

## Status

Accepted

## Context

Set 支撑去重/关系运算（SINTER/SUNION/SDIFF）。

## Decision

采用唯一元素集合 + 插入序：

- SADD/SREM/SISMEMBER/SCARD/SMEMBERS/SPOP/SRANDMEMBER +
  SINTER/SUNION/SDIFF + STORE 变体；
- 元素唯一；空 set 自动删键；STORE 结果为空删除目标键；
- 写路径段锁原子；非 set WRONGTYPE。

## Alternatives

1. List 模拟 Set：唯一性靠扫描；
2. 哈希集合持久化复杂化；
3. 无集合运算：核心价值缺失。

## Consequences

优点：命令完整、运算可用、原子。

缺点：大集合运算 O(n*m)。

风险：SPOP 随机性测试只验数量。

## Implementation

`io.tieringkv.command.SetCommand` +
`src/test/java/io/tieringkv/command/SetCommandFamilyTest.java`。
