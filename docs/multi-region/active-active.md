# 全球 Active-Active

Phase 31 · ADR-0135

## 架构

```text
ActiveActivePipeline（多地域）
  ├─ VersionVector 环回抑制
  ├─ LWW 冲突合并（ts + node）
  └─ ConflictMetrics（冲突率 / 收敛时间）
```

## 语义

- 多地域同时写，广播全部 peer；
- 已见事件抑制（无环回风暴）；
- 冲突按 LWW 收敛，冲突计数与收敛采样可观测；
- 全球读水位（Phase 30）联动。

## 基准（进程内）

双地域写 25–200K ops/s。

## 限制

- CRDT 语义需文档化；跨地域 RTT 待 CI；
- 冲突审计接入网关为 Phase 32。
