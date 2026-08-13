# Multi-Cluster Federation Consistency

## 模型

- 双活 VersionVector 同步模拟；
- 冲突按 LWW 合并；冲突率/收敛时间矩阵；
- 环回抑制复用既有 CRDT 语义。

## 限制

进程内模拟；跨地域真实基准待 Runner。
