# 容灾拓扑指南

Phase 28 · ADR-0115

## 1. 角色

| 角色 | 说明 |
| --- | --- |
| PRIMARY | 主地域（读写） |
| SECONDARY | 备地域（复制 + 可提升） |
| OBSERVER | 仲裁/只读观测 |

## 2. 切换

- 计划内切换：flush-decisions → catch-up → promote → demote（RPO=0）；
- 故障切换：detect → promote-secondary → redirect-gateway
  （RPO 由复制模式决定：SYNC=0，ASYNC=窗口）。

## 3. 演练

`DrDrillRunner` 采样 RTO/RPO；`DrChaosTest`/`Phase28ChaosTest`
覆盖主区故障、计划切换与参数化延迟。
