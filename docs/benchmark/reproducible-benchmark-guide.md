# 可复现 Benchmark 说明（Reproducible Benchmark Guide）

状态：Accepted（2026-08-16）

## 目的

把简历/面试中出现的四组核心性能数字固化为**固定 workload、固定环境记录、
固定轮次**的可复现协议，回答面试官对“测试环境 / workload / 并发 /
baseline / warm-up / P99 统计 / GC / 数据集 / 线程 / 重复轮次”的追问。

## 通用口径原则

1. 所有指标默认**本地进程内 / 回环 / 明确缓存口径**，报告必须写清；
2. 每轮记录环境快照：JDK、OS、CPU 核数、内存、磁盘、`-Xmx`；
3. 预热规则：正式采样前必须预热（JIT + 页缓存 + 连接建立）；
4. 轮次：默认 **5 轮**，报告每轮结果与汇总（min/median/max）；
5. P99 统计：单轮内样本排序取 `p99 = sorted[(int)(N*0.99)]`；
6. GC/JIT：可选 JFR 采集（`-Dtieringkv.argline`），GC 增量用
   `ManagementFactory.getGarbageCollectorMXBeans` 前后差；
7. 结果记录到 `target/reproducible-benchmark/<时间戳>/`，
   并在文档表格留档（见文末模板）。

## 指标 1：内存热路径 GET P99 ≈ 2.5μs

- 测试：[MemoryEngineBenchmarkTest](
  ../../src/test/java/io/tieringkv/benchmark/storage/MemoryEngineBenchmarkTest.java)
- 固定参数：数据集 10K / 100K / 1M；随机键；样本 50,000；预热 2,000；
  单线程直连 Storage API（不含 TCP/RESP/Command）。
- 运行：

```bash
mvn -B -Dsurefire.excludedGroups= -Dtest=MemoryEngineBenchmarkTest test
```

- 输出前缀：`MEM-BENCH GET dataset=...`（P50/P95/P99/throughput）。
- 复现要点：1M 数据集 P99 是简历数字（≈2.5μs）；多轮取中位轮。
- JFR：`-Dtieringkv.argline="-XX:StartFlightRecording=filename=mem.jfr,settings=profile,maxsize=64m,dumponexit=true"`

## 指标 2：网络端到端 GET P99 ≈ 0.19ms（缺口补齐）

- 测试：[NetworkEndToEndLatencyBenchmarkTest](
  ../../src/test/java/io/tieringkv/benchmark/network/NetworkEndToEndLatencyBenchmarkTest.java)
  （新增，2026-08-16）
- 固定参数：回环；**1 连接 × pipeline 1**；数据集 10K；预热 2,000；
  采样 20,000；**5 轮**；RESP + Command 全链路（内存路径，无 WAL）。
- 运行：

```bash
mvn -B -Dsurefire.excludedGroups= -Dtest=NetworkEndToEndLatencyBenchmarkTest test
```

- 输出前缀：`NETWORK-BENCH GET round=...`（P50/P95/P99/throughput）。
- 说明：早期报告 P99 ≈0.19ms 为冒烟口径（连接数/pipeline 未单列）；
  本测试把口径固定为 1 连接 × pipeline 1，结果以本测试为准（预计
  P99 0.1–0.4ms 量级，受本机回环与 JIT 影响）。首次复现
  （2026-08-16，本机）：P50 ≈0.13–0.19ms，P99 ≈0.36–0.42ms，
  吞吐 ≈5–6K ops/s（1 连接 × pipeline 1 同步往返）。

## 指标 3：服务端 Pipeline 128 达 1.14M ops/s

- 测试：[ProductionBenchmarkTest](
  ../../src/test/java/io/tieringkv/benchmark/production/ProductionBenchmarkTest.java)
  `levelBServerBenchmark`
- 固定参数：RESP + Netty + Command + ShardExecutor（内存路径）；
  连接 × pipeline 矩阵 50/100/500 × 1/16/64/128；每组合 100,000 ops；
  记录 `pipeline64` 与 `pipeline128` 各 ≥3 轮。
- 运行（较重，建议 CI / Linux）：

```bash
mvn -B -Dsurefire.excludedGroups= -Dtest='ProductionBenchmarkTest#levelBServerBenchmark' test
```

- 输出前缀：`LEVEL-B connections=... pipeline=... ops/s=...`。
- 复现要点：简历数字为 **500 × 128** 档（响应批处理优化后）；
  baseline = 未开批处理的同链路（195–264K）。

## 指标 4：mmap 1.8–2.1x 与 BlockCache 6.3x

- 测试：[IOBenchmarkTest](
  ../../src/test/java/io/tieringkv/benchmark/io/IOBenchmarkTest.java)
  （mmap vs FileChannel，page cache 热口径）+
  [ColdCacheBenchmarkTest](
  ../../src/test/java/io/tieringkv/benchmark/io/ColdCacheBenchmarkTest.java)
  （BlockCache 冷/热，6.3x）。
- 固定参数：SSD、块 4KB、100K / 1M 数据集、随机 + 顺序、
  冷层直读（不含网络/RESP/WAL）；冷口径需 Linux root
  `drop_caches`（脚本 [cold-cache-bench.sh](../../scripts/cold-cache-bench.sh)）。
- 运行：

```bash
mvn -B -Dsurefire.excludedGroups= -Dtest=IOBenchmarkTest test
scripts/cold-cache-bench.sh both   # Linux root；输出 PHASE61-BENCH
```

- 输出前缀：`IO-BENCH FC-RANDOM/MMAP-RANDOM/FC-SEQUENTIAL/MMAP-SEQUENTIAL`
  （p50/p99/ops/s）、`PHASE61-BENCH`（冷/热）。
- 复现要点：1.8–2.1x 归属 **mmap vs FileChannel**；6.3x 归属
  **BlockCache 冷读基线**，两者口径不同，面试分开讲；单轮/小样本
  存在波动（首次复现 2026-08-16：1M 随机 1.8x、1M 顺序 1.7x，
  100K 随机单轮出现反转），**必须 ≥3 轮取中位**，不得用单轮极值。

## 首次复现记录（2026-08-16，本机单轮）

| 指标 | 结果 | 备注 |
| --- | --- | --- |
| 内存 GET（1M 数据集） | P99 ≈1.5μs | 历史报告 ≈2.5μs；同数量级 |
| 网络 GET（1 连接 × pipeline 1） | P50 ≈0.13–0.19ms / P99 ≈0.36–0.42ms | 固定协议；历史 0.19ms 为冒烟 P99 |
| mmap 1M 随机 | 122K → 217K ops/s（≈1.8x） | 历史 1.8x 复现 |
| mmap 1M 顺序 | 176K → 297K ops/s（≈1.7x） | 同量级 |
| BlockCache 命中率 | 94.79% | 与历史一致 |

> 口径纪律：简历数字来自历史报告；面试以“固定协议可复现测试 + 历史报告”
> 双口径回答，任何单轮波动都如实说明。

## 一键入口

```bash
scripts/reproducible-benchmark.sh              # 内存 + IO + 网络（各 3 轮）
scripts/reproducible-benchmark.sh --rounds 5   # 正式 5 轮
scripts/reproducible-benchmark.sh --server     # 追加 Level B（较重）
scripts/reproducible-benchmark.sh --cold       # 追加 cold-cache（需 Linux root）
scripts/reproducible-benchmark.sh --quick      # 1 轮冒烟
```

输出目录：`target/reproducible-benchmark/<时间戳>/`（env.txt +
memory.txt + io.txt + network.txt + [server.txt] + [cold.txt] + SUMMARY.md）。

## 结果记录模板

| 日期 | 环境 | JDK | CPU | 数据集 | workload | 轮次 | P50 | P95 | P99 | 吞吐 | GC 增量 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| YYYY-MM-DD | 本机/CI | 21.x | 核数 | 1M | 随机 GET | 5 | … | … | … | … | … | 页缓存热/回环 |

## 参考

- [memory-engine-report.md](memory-engine-report.md)
- [phase10-performance-report.md](phase10-performance-report.md)
- [io-report.md](io-report.md)
- [phase61-production-closure-report.md](phase61-production-closure-report.md)
- [measurement-conventions.md](../testing/measurement-conventions.md)
