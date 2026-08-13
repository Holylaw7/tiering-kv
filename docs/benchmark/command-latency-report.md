# Command Latency Report

## 说明

命令延迟为本地进程内口径（MemTable 直连 + WAL 可选），用于回归对比；
网络端到端延迟见 Phase 9 报告。

## 摘要

- 原子字符串操作（INCR/APPEND/GETSET）：微秒级；
- TTL 查询：微秒级（TTLManager 惰性 + 主动）；
- SCAN 游标：O(count) 切片，首轮 O(N) 快照；
- 并发测试：100 线程 × 1000 INCR 0 lost update。
