# Phase 64 — P1c Concurrency & Performance Report

## 1. WAL 并行恢复（ADR-0329）

`ParallelRecoveryManager`：WAL 段并行解析（解码 + CRC），主线程按段序
串行应用。多段恢复结果与串行等价（80 条全等），损坏段截断停止后续。
1M 记录恢复延迟随段数扩展（并行度 = CPU 核数）。

## 2. 命令路径 allocation 基线（ADR-0330）

ThreadMXBean（com.sun.management）近似测量（JDK 17.0.7）：

| 指标 | 值 |
| --- | --- |
| async PING 每请求分配 | 64.1 bytes（Callback 版 + 无参命令 key 缓存后） |

优化项：无参命令 key 字节缓存（PING/ECHO 等不再每请求分配 byte[]），
Callback 路径无 CompletableFuture 分配。目标 -30% 已在 Phase 10
Callback 化后达成（对比 Future 版历史基线）。

## 3. JDK 21 虚拟线程 POC（ADR-0331）

`VirtualThreadsPoc`（JDK 21.0.12，反射创建 VT 执行器）：

| 指标 | 值 |
| --- | --- |
| 执行器 | ThreadPerTaskExecutor（虚拟线程） |
| 100K 任务吞吐 | 941,818 ops/s |
| JDK 17 回退 | cached pool（GatewayRuntimeExecutorTest 验证） |

GatewayRuntime 支持 `--virtual-threads true` 开关；默认平台线程。
完整网关连接压测（1k/10k 连接）需 JDK 21 运行时 + redis-benchmark
等价工具，列入 phase64 后续。

## 复现

```bash
# allocation 基线（JDK 17）
mvn -Dsurefire.excludedGroups= -Dtest=CommandAllocationBenchmarkTest test
# VT POC（JDK 21）
E:\Java\microsoft-jdk-21\bin\java -cp target/classes \
  io.tieringkv.runtime.VirtualThreadsPoc 100000
```
