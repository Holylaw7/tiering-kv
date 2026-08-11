# Phase 21 真实混沌报告：Docker + tc netem

Phase 21 · 2026-08-11 · Docker 29.6.1 / WSL Ubuntu

## 1. 拓扑

```text
node1 : MultiRaft r1/r2 + 7000（Gateway 预留）
node2 : MultiRaft r1/r2 + 7000
node3 : Metadata/副本 + 7000
```

## 2. 执行结果

| 故障 | 注入 | 结果 |
| --- | --- | --- |
| 网络延迟/丢包/重复 | `tc qdisc add dev eth0 root netem delay 100ms loss 5% duplicate 2%` | 三节点存活，日志正常 |
| 网络分区 | `docker network disconnect/connect deploy_tiering-cluster-net node1` | node1 隔离期间 node2/3 存活，恢复后回集群 |
| 进程 kill | `docker kill -s 9 node2` | node1/node3 保持多数派；node2 重启后重新监听 7000 |
| 进程 restart | `docker start node2` | 日志确认 `listening on 7000, groups=[r1, r2]` |

## 3. 环境修复（构建链路）

- 移除 `dependency:go-offline`（netty-tcnative `${os.detected.classifier}`
  无法在 go-offline 阶段解析）；
- jar 增加 Main-Class（ClusterMain）+ maven-shade-plugin 生成可执行 fat jar；
- compose 构建上下文指向仓库根 + NET_ADMIN 能力。

## 4. 未执行（如实登记）

- disk slow：未注入 IO 延迟（需 device-mapper/限流）；
- disk full：未注入 ENOSPC；
- 跨机事务端到端：容器运行时尚未托管事务 participant/网关（TD-043）。

## 5. 复现

```bash
docker compose -f deploy/docker-compose.cluster.yml up -d
docker run --rm --cap-add NET_ADMIN --net container:tiering-c-node1 \
  alpine sh -c "apk add iproute2 && tc qdisc add dev eth0 root netem delay 100ms loss 5% duplicate 2%"
docker kill -s 9 tiering-c-node2 && docker start tiering-c-node2
```
