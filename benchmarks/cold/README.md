# benchmarks/cold

冷存储基准（Phase 5）。

基准代码位于 Maven 标准测试布局：
`src/test/java/io/tieringkv/benchmark/cold/ColdBenchmarkTest.java`。

运行：

```bash
mvn -Dtest=ColdBenchmarkTest test
```

覆盖：

- SSTable 写吞吐：100K / 1M 键（目标 >100MB/s）；
- 1M 表随机 GET：P50 / P95 / P99（目标 P99 < 5ms）；
- Bloom Filter FPR（目标 <1%）；
- 全量合并：输入/输出体积与吞吐。

注：随机 GET 指标含 OS page cache 影响（热缓存口径）；Phase 9 增加
cold-cache benchmark（drop cache 磁盘冷读）。

结果报告：[docs/benchmark/cold-report.md](../../docs/benchmark/cold-report.md)
