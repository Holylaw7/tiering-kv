# ADR-0069: Cross Machine Deployment

## Status

Accepted

## Context

需要真实三节点容器化部署：独立 JVM / 磁盘 / 网络，且支持 tc netem
混沌注入。当前本机无 Docker 守护进程，交付可执行部署产物 +
等价本地混沌验证。

## Decision

- `deploy/docker-compose.cluster.yml`：node1/node2/node3，每节点独立
  容器 + 数据卷 + bridge 网络；运行 ClusterMain（Storage Raft）；
  Gateway/Metadata 角色在文档中标注（ClusterMain 演进）；
- tc netem：复用 `deploy/chaos-netem.sh`（latency/loss/partition/
  disk-slow/kill-9）；
- 混沌验证：`CrossMachineChaosTest`（≥20）在传输层等价注入
  （延迟/丢包/分区/重启/快照追赶/迁移中断），真实 TCP 回环覆盖
  消息路径；
- 环境限制如实记录：容器跨机执行需 Linux+Docker。

## Alternatives

1. 单进程模拟多节点：无法验证独立 JVM/磁盘，否决。
2. 本机直接启动多 JVM：网络命名空间不隔离，否决。

## Consequences

优点：部署产物开箱即用；混沌脚本与测试双路径验证。

缺点：本机无法端到端执行容器混沌（环境限制）。

风险：容器网络 DNS 与端口映射需在真实环境校验。

## Implementation

- `deploy/docker-compose.cluster.yml`
- 测试：CrossMachineChaosTest（≥20）。
