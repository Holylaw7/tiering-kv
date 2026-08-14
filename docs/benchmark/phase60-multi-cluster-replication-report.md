# Phase 60 — v4 M3 Multi-Cluster Replication Benchmark Report

## 口径

- 环境：本地 Windows / JDK 17 开发机基线；
- 双 MultiRaftEndpoint（127.0.0.1 随机端口）RPC 通道；
- 5,000 次事件发送（含编码 + RPC 往返 + 目标端 LWW 决策 + 应用），
  预热 1 次后计时；
- 每次 send 同步等待 REPLICATION_RESPONSE（最严口径）。

## 结果

| 指标 | 值 |
| --- | --- |
| 复制事件吞吐（同步 ack） | 5,748 ops/s（本地基线） |

## 结论

- 复用 MultiRaftEndpoint RPC 的复制通道成立：编码 + 传输 + 解码 +
  LWW 决策 + 应用一条链全通；
- 同步 ack 口径下吞吐受 RPC 往返主导；批量/异步 ack 优化列入 M3
  后续（与 ReplicationPipeline ASYNC 模式对齐）。

## 复现

```bash
mvn -Dsurefire.excludedGroups= -Dtest=CrossClusterReplicationBenchmarkTest \
  -DfailIfNoTests=false test
```
