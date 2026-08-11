# Geo CRDT 规模验证报告

Phase 29 · ADR-0122 · 进程内模拟（如实标注）

## 规模

- CrdtScaleSimulator：多节点 × 多键并发写，任意合并顺序收敛；
- 10 万键 × 5 轮模拟 ≈109ms；
- LWW 1000 次写入确定性收敛。

## 时钟校准

- HybridClockCalibrator：采样估计节点偏差；
- 偏差 ±1000ms 时 LWW 时间戳调整可解释；
- 超阈值告警（Phase 29 Goal 7 接线）。

## 限制

- 进程内单副本模拟；跨机偏差待 CI/裸机；
- 百万键真实验证待 Runner。
