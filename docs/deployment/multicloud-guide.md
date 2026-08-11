# 多云部署与迁移

Phase 31 · ADR-0136

## 配置

`MulticloudConfig(storageClass, ingressClass, registry, gatewayReplicas)`
参数化清单差异（云存储类/入口/镜像仓库）。

## 迁移

`CloudMigration`：跨环境数据搬迁（源 → 目标 + 校验）。

## 限制

- 真实多云部署（EKS/GKE/AKS）待 Runner；
- 迁移版本屏障待 Phase 32。
