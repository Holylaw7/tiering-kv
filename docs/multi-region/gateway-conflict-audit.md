# 全球多活网关冲突审计

Phase 32 · ADR-0141

## 组件

- `RegionAffinityRouter`：key hash → 首选地域（写亲和）；
- `ConflictAuditLog`：region/key/ts/winner 审计；
- 网关读水位校验（Phase 30 GlobalReadRouter）。

## 语义

- 亲和路由稳定（同 key 同地域）；
- 冲突审计完整可查询（byKey）；
- 删除/环回由 Active-Active 管道兜底。
