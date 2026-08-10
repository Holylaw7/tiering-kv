# benchmarks/storage

内存引擎基准（Phase 2）。

基准代码位于 Maven 标准测试布局：
`src/test/java/io/tieringkv/benchmark/storage/MemoryEngineBenchmarkTest.java`
（映射规则见 AGENT_CONTEXT TD 说明）。

运行：

```bash
mvn -Dtest=MemoryEngineBenchmarkTest test
```

覆盖：

- GET 延迟：数据集 10K / 100K / 1M，指标 P50 / P95 / P99 / 吞吐；
- 并发写：10 / 50 / 100 线程 × 1000 次 PUT，验证失败数 = 0 且最终 size 正确。

结果报告：[docs/benchmark/memory-engine-report.md](../../docs/benchmark/memory-engine-report.md)
