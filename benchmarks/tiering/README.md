# benchmarks/tiering

自动调度基准（Phase 6）。

基准代码位于 Maven 标准测试布局：
`src/test/java/io/tieringkv/benchmark/tiering/TieringBenchmarkTest.java`。

运行：

```bash
mvn -Dtest=TieringBenchmarkTest test
```

覆盖：

- 自动 Flush：100K / 200K 条目的延迟与吞吐；
- 异步迁移：100K / 1M 条目的 ops/s 与成功率；
- 内存压力：连续写入 + 配额限制下背压稳定性（内存不超过上限）。

结果报告：[docs/benchmark/tiering-report.md](../../docs/benchmark/tiering-report.md)
