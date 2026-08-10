# Phase 16 跨机部署与混沌指南

Phase 16 · 2026-08-10

## 1. 拓扑

```text
node1（metadata / 区域 leader 候选）
node2（region leader 候选）
node3（replica）
         │  同一 Docker bridge 网络（独立 JVM / 独立数据卷 / 独立网络命名空间）
         ▼
每个节点：ClusterMain
  ├── MultiRaftEndpoint（单端口 7000，组前缀路由）
  ├── r1 / r2 两个 Raft 组（Region [a,m) / [m,z)）
  ├── 独立数据目录 /data/<node>/<group>（日志/状态按组隔离）
  └── RegionManager + RegionEpoch 路由保护
```

角色（leader/replica）由 Raft 选举动态产生，非静态配置。

## 2. 启动

```bash
cd deploy
docker compose up --build -d
docker compose ps
```

节点启动后各自选举 r1/r2 leader；无 Redis 网关（下一阶段），
可通过 `docker logs tiering-node1` 观察组状态。

## 3. 混沌注入（需要 Docker 守护进程 + Linux 内核）

```bash
./chaos-netem.sh tiering-node1 latency 100      # 100ms 延迟
./chaos-netem.sh tiering-node1 loss 5           # 5% 丢包
./chaos-netem.sh tiering-node1 partition tiering-node3  # 分区
./chaos-netem.sh tiering-node1 heal             # 恢复
./chaos-netem.sh tiering-node1 disk-slow        # IO 降级（尽力而为）
./chaos-netem.sh tiering-node1 kill             # kill -9
```

## 4. 验证项

- 无数据丢失：已提交写入在分区/击杀/重启后保留；
- leader 选举：节点击杀后 ≤5s 选出新 leader；
- replica catch-up：分区恢复/重启后追平；
- 组隔离：r1 故障不影响 r2。

## 5. 环境限制（如实声明）

本开发机（Windows）Docker CLI 存在但守护进程未运行，且无 Linux
`tc netem`；因此跨机容器混沌未在本机执行。等价验证已通过：

- `ChaosClusterTest`（20 项）：多 Region × 多 Raft 组故障注入
  （延迟/丢包/分区/磁盘慢/leader 击杀/重启追平/epoch 保护），
  全部通过；
- 真实 TCP 多组单端口路由由 `MultiRaftTransportTest`（11 项）覆盖；
- 混沌发现的 Raft 缺陷（新 leader 不回填滞后副本）已修复并加回归测试。

跨机容器执行需在具备 Docker + Linux 的机器上按第 2/3 节运行。
