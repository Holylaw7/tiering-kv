# Phase 10 性能报告（Before / After）

环境：Windows 11，JDK 21.0.12，20 核，`-Xmx1g`，回环（客户端与服务器同 JVM）。

## Level B：响应批处理（ADR-0032）前后对比

| 连接 × pipeline | Before ops/s | After ops/s | 提升 |
| --- | --- | --- | --- |
| 500 × 64 | 218–231K | **465K** | ~2.1× |
| 100 × 64 | 189K | 547K | ~2.9× |
| 500 × 128 | 195–264K | **1.14M** | ~4.3× |
| 500 × 16 | 163–206K | 270K | ~1.4× |
| 500 × 1 | 73–121K | 77K | ≈（低延迟路径经批处理器） |

目标：pipeline64 × 500 > 400K ops/s ✅（465K）。

## Level C：生产全链路（无回退，且提升）

| Workload | Before | After |
| --- | --- | --- |
| A（90/10） | 115–165K | 154K |
| B（70/30） | 149–157K | 240K |
| C（热点 10 键） | 158–178K | 326K |

## Allocation / GC 分析

- 对象削减（ADR-0033）：每请求 CompletableFuture + whenComplete Lambda +
  Outcome → 单个 `Pending` 回调；响应 ByteBuf 每连接复用（批内一次分配）；
- 实测 GC 增量：Before ≈ 7–11，After ≈ 8（同量级，小样本）；
- 吞吐提升 ~2–4× 主要来自系统调用与对象分配下降；JFR allocation rate
  深度对比需手动采集（TD-021 验收项）。

## 结论

瓶颈已从"每响应一次写 + 每请求 Future 链"转移；批处理 + 回调执行在
pipeline 场景获得 2–4× 收益，Level C 无回退。低并发单请求路径保持
近似立即发送（排空即 flush）。
