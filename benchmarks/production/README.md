# benchmarks/production

Phase 9 生产三级基准（ADR-0029）。

基准代码位于 Maven 标准测试布局：
`src/test/java/io/tieringkv/benchmark/production/ProductionBenchmarkTest.java`。

运行：

```bash
mvn -Dtest=ProductionBenchmarkTest test
```

覆盖：

- Level A：内存引擎（GET/SET 随机/单键/热点、80/20 混合）；
- Level B：服务端（50/100/500 连接 × pipeline 1/16/64/128）；
- Level C：生产全链路 Workload A/B/C/D（含内存压力迁移）。

报告：docs/benchmark/phase9-{memory,server,production}-report.md、
capacity-model.md、deployment-profile.md。
