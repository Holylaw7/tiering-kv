# ADR-0359：内存上限与 OOM 拒绝语义（TD-083）

## Status

Accepted（2026-10-24）

## Context

对比实测（docs/benchmark/tiering-vs-redis7.md）暴露：把 JVM 堆压到
`-Xmx512m`、引擎上限 256 MB，持续写入约 80 万键后出现

```
java.lang.OutOfMemoryError: Java heap space
Error: Connection reset by peer
```

进程仍在（`docker ps` 显示 Up），但 Netty 事件循环线程已死，所有新连接
被重置、`DBSIZE` 无响应——**静默故障**，且无法自愈。

根因有两层：

1. `MemoryManager` 只计量"键 + 值字节数"，不含 SkipList 节点、对象头、
   WAL 缓冲与 16 个分片队列，真实堆占用可达计量值的 5 倍以上；
2. 超限只驱动异步淘汰/迁移，**没有任何"拒绝写入"的闸门**，写入速率
   超过迁移速率时堆必然先触顶。

## Decision

三层防护：

1. **超限判定纳入真实堆占用**：`MemoryManager.isOverLimit()` =
   计量超限 **或** 堆占用 > `-Xmx` × 0.9（系统属性
   `-Dtieringkv.memory.heap-ratio` 可调）。堆判定前先做一次限频
   （1 秒）全量 GC——`totalMemory - freeMemory` 含未回收垃圾，
   直接据此判定会导致"删完数据仍拒绝写入"的自锁。
2. **写命令闸门**：`CommandEngine` 在内存压力下拒绝**分配型**写命令，
   返回 `OOM command not allowed when used memory > 'maxmemory'.`
   （与 Redis 对齐）；**释放型**写命令（`DEL`/`FLUSHALL`/`EXPIRE`/
   `HDEL`/`SPOP` 等）始终放行，保证可自救。读命令不受影响。
3. **OOM 兜底**：`CommandHandler` 捕获 `OutOfMemoryError`（命令执行与
   `exceptionCaught` 两处），记录日志并关闭该连接，避免异常逃逸导致
   事件循环线程终止。

## Alternatives

- **只调小水位线**：治标，位移压力到下一个阈值，仍会 OOM，否决；
- **按 `heapUsed/maxHeap` 直接拒绝**：突发写入后垃圾未回收会永久拒绝，
  形成自锁（本轮实测复现），必须配合 GC 或滑动窗口，否决单指标方案；
- **引入有界队列 + 阻塞写**：会让客户端长时间挂起，且无法解决堆占用
  与计量脱节的根因，否决。

## Consequences

- 内存超限时表现为**可预期的错误码**而非进程崩溃，符合 Redis 客户端预期；
- 释放型命令放行，运维可在线恢复；
- 堆阈值判定会引入最多每秒一次的 `System.gc()`（仅在已超阈值时），
  极端情况下有停顿风险，可通过 `-Dtieringkv.memory.heap-ratio` 调高阈值规避；
- 计量口径仍未覆盖对象开销，真正的容量承诺仍需按实测值打折（TD-084 待办）。

## Implementation

- `storage/memory/MemoryManager.java`（堆感知 + 限频 GC）
- `command/WriteCommands.java`（写命令分类 + 释放型白名单）
- `command/CommandEngine.java`（写闸门，`OOM_ERROR` 常量）
- `network/connection/CommandHandler.java`（OOM 兜底）
- `Main.java`（接线 `memoryManager::isOverLimit`）
- 测试：`CommandEngineMemoryGuardTest`(5)、`WriteCommandsTest`(6)、
  `MemoryManagerHeapLimitTest`(5)

## Verification

容器内复现原场景（`-Xmx512m` + 引擎 256 MB）：

| 步骤 | 修复前 | 修复后 |
| --- | --- | --- |
| 压测到超限 | 连接被重置，事件循环线程 OOM 死亡 | 返回 `OOM command not allowed...` |
| 超限后 `PING` | 无响应 | `PONG` |
| 超限后 `SET` | 连接重置 | 稳定返回 OOM 错误 |
| `FLUSHALL` | 不可能（连接已死） | `OK` |
| flush 后 `SET` | — | `OK` |
| 日志 | `OutOfMemoryError` + `Connection reset` | 无 |
