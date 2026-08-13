# redis-cli Compatibility Guide

## 支持的命令

PING / ECHO / SET / GET / DEL / EXISTS / INFO / INCR / DECR /
INCRBY / DECRBY / APPEND / STRLEN / GETSET / SETNX / SETEX /
PSETEX / GETDEL / GETRANGE / SETRANGE / TTL / PTTL / EXPIRE /
PEXPIRE / EXPIREAT / PEXPIREAT / PERSIST / MGET / MSET / MSETNX /
DBSIZE / FLUSHDB / FLUSHALL / SCAN / TYPE / CONFIG / CLIENT /
COMMAND。

## 示例

```bash
redis-cli SET k 10
redis-cli INCR k
redis-cli EXPIRE k 100
redis-cli TTL k
redis-cli SCAN 0 COUNT 100
redis-cli MSET a 1 b 2
redis-cli MGET a b
```

## 限制

- 无 hash/list/set/zset 数据结构（Phase 52+）；
- CLIENT GETNAME 恒 nil；
- 集群模式多键命令要求同 slot（hash tag）。
