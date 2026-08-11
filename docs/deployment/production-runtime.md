# 生产运行配置冻结（Phase 23）

## JVM

- heap：-Xmx1g（运行时角色可降为 512m）
- GC：默认 G1；长事务/低延迟优先 ZGC（Java 21）
- 线程：Netty boss=1、worker=CPU 核数；协调器调度线程 1

## Transaction

- txn.ttl-seconds: 60
- txn.max-duration-seconds: 300
- heartbeat.interval: 10s（锁续约）
- lock.timeout: 60s（participant 锁 TTL）

## Raft

- election timeout: 100–180ms（LeaderElection(100,80)）
- heartbeat: 25ms
- snapshot: 1024 条日志阈值

## Network

- rpc timeout: 3s
- retry: 2 次 + 客户端 3 次重试
- backoff: 10ms 线性

## 部署

```bash
docker compose -f deploy/docker-compose.transaction.yml up -d
```
