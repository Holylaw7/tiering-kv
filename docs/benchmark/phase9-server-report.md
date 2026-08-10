# Phase 9 · Level B 服务端报告

环境：同上；拓扑 = 回环 Client（同 JVM）→ RESP → Netty → CommandEngine →
KeyShardExecutor(20) → MemTable；数据集 10K 键，GET-only，每配置 100K 操作。

| 连接数 | pipeline=1 | 16 | 64 | 128 |
| --- | --- | --- | --- | --- |
| 50 | 73K | 173K | 181K | 195K |
| 100 | 83K | 171K | 189K | 206K |
| 500 | 113–121K | 163–206K | **218–231K** | 195–264K |

目标：pipeline 64 > 500K ops/s ⚠️ **未达成（实测峰值 218–231K，≈44–46%）**；
多次运行取范围，单次不作为结论。

## 瓶颈分析

- 每命令 CompletableFuture 分配 + ResponseSequencer 同步 TreeMap +
  每响应独立 ByteBuf 编码：协议/调度层为当前主导成本；
- Netty 事件循环 → 分片 worker → 事件循环回写：两次线程切换；
- 客户端与服务器同 JVM（回环），存在 CPU 竞争（独立进程预期 +20~40%）；
- 内存直连（Level A）≈ 4.7M vs 服务端 218K：**网络 + 协议占全链路 95%+**。

## 优化建议（Phase 10）

- 批量响应写（pipeline 聚合 flush）；复用 response buffer 减少分配；
- 独立进程压测拓扑（ADR-0029 生产拓扑）；
- 按核数扩分片 + 响应保序器改并发队列。
