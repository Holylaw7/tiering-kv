# benchmarks/cache

缓存/淘汰基准（Phase 3）。

基准代码位于 Maven 标准测试布局：
`src/test/java/io/tieringkv/benchmark/cache/CacheEvictionBenchmarkTest.java`。

运行：

```bash
mvn -Dtest=CacheEvictionBenchmarkTest test
```

覆盖：

- LFU：100K 键、1M 次访问（50 万 GET + 50 万 PUT），指标：查找/更新延迟
  （avg / P99）与内存开销估算；
- 淘汰决策延迟（Eviction decision latency）：100K / 1M 条目下的候选选择延迟，
  目标 P99 < 1ms；仅覆盖候选选择，不含迁移 / IO。

结果报告：[docs/benchmark/cache-eviction-report.md](../../docs/benchmark/cache-eviction-report.md)
