# Operations Runbook

## 启动 / 停机

- 启动：`java -jar tiering-kv.jar --nodeId n1 --port 6379`
- 优雅停机：SIGTERM（drain 请求 → flush 响应 → WAL force →
  checkpoint）

## 监控

- INFO / INFO CLUSTER / INFO TRANSACTION；
- Prometheus MetricsExporter；跨 RPC 追踪（observability）。

## 备份 / 恢复

- 备份：快照 + WAL + MVCC 索引（docs/operations/
  upgrade-backup-drills.md）；
- 演练：`./scripts/restore-drill.sh <backup-dir>`。

## 升级

- 逐节点滚动升级 + 追平等待 + 数据奇偶校验：
  `./scripts/upgrade-drill.sh <nodes>`。

## 故障排查

- WAL 损坏：日志尾部截断自动忽略；
- Raft 无主：检查心跳/网络分区；查看 INFO RAFT；
- 内存压力：水位 70/85/95，CRITICAL 限写；
- 事务失败：ExecJournal 审计 outcome。

## SLO

本机 LOCAL 基线见 final-performance-whitepaper.md；跨地域 RTO/RPO
待真实 Runner。
