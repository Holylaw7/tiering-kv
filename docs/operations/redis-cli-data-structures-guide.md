# redis-cli Data Structures Guide

## 示例

```bash
redis-cli HSET user:1 name alice age 30
redis-cli HGETALL user:1
redis-cli RPUSH queue job1 job2
redis-cli LRANGE queue 0 -1
redis-cli SADD tags java redis
redis-cli SINTER tags other
redis-cli ZADD leaderboard 100 alice 90 bob
redis-cli ZRANGE leaderboard 0 -1 WITHSCORES
redis-cli SUBSCRIBE news
redis-cli PUBLISH news hello
```

## 限制

- 高级命令（HSCAN/ZRANGEBYLEX/LINSERT/LMOVE 等）待 Phase 53+；
- RESP3 连接级接线待 Phase 53。
