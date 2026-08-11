# Phase 22 混沌报告：事务可靠性与磁盘故障

Phase 22 · 2026-08-11

## 1. 网络/进程混沌（沿用）

- Docker 三节点 + tc netem（100ms/5%/2%）存活；
- 网络分区恢复、kill -9/restart 回集群（详见 phase21-real-chaos-report）。

## 2. 磁盘故障（TD-044 部分关闭）

| 故障 | 注入 | 结果 |
| --- | --- | --- |
| disk full on commit | in-JVM FailOnSecondPut | 决策持久化 → 恢复补完，无数据丢失 |
| disk slow | in-JVM SlowStorage（1–20ms） | 提交一致，无异常 |
| WAL 损坏（尾部） | 截断追加半条记录 | 尾部容忍，已提交记录保留 |
| WAL 损坏（中部） | 翻转字节 | 显式抛错，不静默恢复 |
| readonly 元数据日志 | proposer 抛 IOException | 无状态变更（无幻影） |

真实容器注入受限：

- VM 数据盘 930G 空闲，dd 填满不可行；
- 容器以 root 运行，chmod -w 不阻止写入；
- WAL 截断路径未命中（容器内数据目录布局），未形成有效证据。

→ 登记 TD-046；in-JVM 语义测试兜底。

## 3. 复现

```bash
mvn -Dtest=Phase22DiskChaosTest test
```
