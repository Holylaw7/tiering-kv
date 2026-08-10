# benchmarks/io

IO 优化基准（Phase 8）。

基准代码位于 Maven 标准测试布局：
`src/test/java/io/tieringkv/benchmark/io/IOBenchmarkTest.java`。

运行：

```bash
mvn -Dtest=IOBenchmarkTest test
```

覆盖：

- mmap vs FileChannel：100K / 1M 键随机 + 顺序读（P50/P95/P99/吞吐）；
- BlockCache：冷读 / 热读 / 混合（命中率）；
- 内存概况：MemoryPool 分配/复用/峰值 + GC 计数。

注意：10M 键基准文件较大，需手动运行（自动化套件含 100K / 1M）。

结果报告：[docs/benchmark/io-report.md](../../docs/benchmark/io-report.md)
