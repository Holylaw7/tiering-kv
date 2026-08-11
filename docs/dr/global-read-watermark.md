# 全球读水位联动

Phase 30 · ADR-0129

## 水位源

`GlobalReadRouter` 支持两种水位：

- 静态 Map（测试/简单部署）；
- `Supplier<Long>`（复制管道 / 双向 CRDT 已应用水位）。

## 陈旧度 SLA

- `recordStaleness(millis)` 采样；
- `stalenessPercentiles()` 输出 p50/p95/p99；
- 水位滞后可接入 AlertManager（Phase 29 Goal 7）。

## 模式

- STRONG：仅本地水位达标可读；
- BOUNDED：复制水位兜底可读。
