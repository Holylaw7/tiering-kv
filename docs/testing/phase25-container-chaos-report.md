# Phase 25 容器混沌报告

Phase 25 · 2026-08-11 · TD-048 交付物

## 1. 管道

`.github/workflows/transaction-e2e.yml` 扩展为四 job：

```text
jvm-e2e          JVM 等价 E2E + 磁盘混沌 + 网络元数据 Raft
container-e2e    build → up --wait → 健康检查 → 冒烟 → 容器故障注入 → 冒烟 → logs → cleanup
kind-e2e         kind 集群 → 清单应用 → StatefulSet 就绪 → PDB 驱逐 → 网关冒烟
block-device-chaos  loop device + disk full + remount,ro + 门控测试
```

## 2. 容器故障注入（scripts/container-chaos.sh）

| Case | 注入 | 验证 |
| --- | --- | --- |
| kill coordinator | docker kill/start txn-coordinator | 冒烟 SET/GET 恢复 |
| kill participant | docker kill/start txn-participant-a | 冒烟 SET/GET 恢复 |
| kill metadata | docker kill/start txn-meta | 新 leader 承接 |
| network partition | tc netem loss 100%（2s） | 分区恢复后冒烟通过 |

## 3. 执行状态（如实记录）

- JVM 等价套件（CiTransactionE2E 31 + 参数化 40 项）本地全绿；
- 容器故障注入脚本语法与 compose 命令已就绪；
- 真实 GitHub Actions 运行记录待 push 触发（本机无 Docker Runner）。

## 4. 验收

同一 Runner 连续 3 次全绿后关闭 TD-048。
