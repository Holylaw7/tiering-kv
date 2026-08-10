# benchmarks/wal

WAL 基准（Phase 4）。

基准代码位于 Maven 标准测试布局：
`src/test/java/io/tieringkv/benchmark/wal/WALBenchmarkTest.java`。

运行：

```bash
mvn -Dtest=WALBenchmarkTest test
```

覆盖：

- Append：100K / 1M 记录（EVERY_SEC，近似 group commit），指标 P50/P95/P99
  与吞吐，目标 P99 < 1ms；
- Recovery：100K / 1M 记录恢复耗时（目标秒级）。

结果报告：[docs/benchmark/wal-report.md](../../docs/benchmark/wal-report.md)
