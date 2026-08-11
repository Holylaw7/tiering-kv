# Enterprise SaaS Control Plane 路线图

Phase 27 · ADR-0113

## 当前原型

- `ClusterTenant`（租户/集群/配额）
- `TenantQuotaValidator`（region/存储超限拒绝）

## 路线图

| 版本 | 能力 |
| --- | --- |
| v1.2 | 多集群元数据、按租户生成 TieringKVCluster（Operator 联动） |
| v1.3 | 审计日志、用量计费、配额动态调整 |
| v2.0 | 完整 SaaS 控制平面（自助开通/租户隔离/计费） |

## 边界

配额模型为原型；真实多租户隔离需网络/存储层配合（Phase 28+）。
