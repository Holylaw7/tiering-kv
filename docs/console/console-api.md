# 企业控制台 API

Phase 31 · ADR-0137

## 端点（原型）

- `listTenants(token)`：租户列表（ADMIN）；
- `createTenant(token, tenant)`：租户创建（ADMIN）；
- `metrics(token)`：指标快照（READ）；
- `alerts(token)`：告警列表（ADMIN）。

## 安全

RBAC（CredentialManager + Permission，ADR-0110）。

## 限制

- REST 传输层待 Phase 32（当前为 API 模型）；
- 完整 UI/自服务门户为 Phase 32。
