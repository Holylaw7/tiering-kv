# benchmarks/concurrency

并发基准（Phase 7）。

基准代码位于 Maven 标准测试布局：
`src/test/java/io/tieringkv/benchmark/concurrency/ConcurrencyBenchmarkTest.java`。

运行：

```bash
mvn -Dtest=ConcurrencyBenchmarkTest test
```

覆盖：

- GET / SET / Mixed 吞吐与 P50/P99（10 / 50 / 100 / 256 线程）；
- 热点键 90% 流量（HotKeyReadCache + 请求合并）；
- 分片执行器 vs 单执行器对比。

结果报告：[docs/benchmark/concurrency-report.md](../../docs/benchmark/concurrency-report.md)
