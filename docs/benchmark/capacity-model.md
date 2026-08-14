# 生产容量模型（Capacity Model）

## v4 M4 可计算模型（ADR-0322）

新增 `CapacityModel`（io.tieringkv.capacity）：输入 QPS / 值大小 /
读占比 / 副本数 / 保留天数 / 活跃键数，输出内存、磁盘、吞吐预算、
延迟预算四维估算。公式：

- 内存 = 活跃键数 × (值大小 + 96B 开销) × 副本数；
- 磁盘 = QPS × 写占比 × 值大小 × 保留天数 × 86400 × 副本数；
- 吞吐预算 = QPS × 1.2（20% headroom）；
- 延迟预算 = 读为主 5ms / 写为主 10ms。

测试：CapacityModelTest（边界 / 校验 / 四维计算）。
常数随基准数据校准（phase61 报告）。

校准环境：20 核 / 8G 级单机、SSD、JDK21、`-Xmx1g`（基准）。公式以实测为准。

## CPU 模型

```text
QPS/core：
  内存直连（Level A）   ≈ 200–250K ops/s/core
  服务端（Level B p64） ≈ 11K ops/s/core
  生产全链路（Level C） ≈ 8K ops/s/core
单机估算：8 核 ≈ 64–90K ops/s（全链路）
瓶颈：网络 + RESP 协议/调度占主导（A→C 相差 ~30×）
```

## 内存模型

```text
MemTable ≈ (66B + key + value) × 热键数
LFU 索引 ≈ 96B/键（热度跟踪）
BlockCache ≈ 容量(块) × 8KB（池化 off-heap，实测 2048 块 ≈ 25MB）
JVM 开销 ≈ 1.5× 堆内模型；DirectMemory 建议 ≥ 512MB
```

## 磁盘模型

```text
WAL ≈ (50B + key + value) × 写入量（EVERY_SEC，滚动回收）
SSTable ≈ 数据 + 25%（块/索引/Bloom 开销）
Compaction 写放大 ≈ 全量 1×（触发时）；合并空间回收实测 73%
1M 键 × 100B ≈ 60MB SSTable；恢复 1M WAL ≈ 1.0–1.2s
```

## 网络模型

```text
GET ≈ 80B/op（请求 + 响应）；SET ≈ 200B/op
218K ops/s（p64）≈ 17MB/s —— 回环下网络非瓶颈；跨网络以链路带宽校准
```

## 单节点容量结论

- 全链路稳定吞吐 ≈ 115–180K ops/s（本机口径）；
- 容量上限 = min(CPU 模型, 内存配额, 磁盘吞吐)；当前以网络/协议层为第一瓶颈；
- 成本模型：内存为主要成本项（热键 × ~160B + 缓存），磁盘按 SSTable 体积
  与合并放大估算；详见部署画像。
