# LSM-Tree 详细设计（LSM Design）

状态：草稿（Phase 5 细化）

## 范围

SSTable 文件格式、层级（L0..Ln）、compaction 策略、Bloom Filter 参数。

## 待定项

- size-tiered vs leveled compaction（Phase 5 决策，需 ADR）；
- SSTable 块大小与索引间隔；
- Bloom Filter 误判率目标（建议 ≤1%）与每文件位图尺寸；
- compaction 触发水位与后台线程数。

## 约束

- 文件格式遵循 ADR-0005；
- 读放大与写放大必须纳入 metrics 观测。
