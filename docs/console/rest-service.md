# 控制台 REST 服务

Phase 32 · ADR-0139

## 端点

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | /tenants | ADMIN |
| POST | /tenants | ADMIN |
| GET | /metrics | READ |
| GET | /alerts | ADMIN |

鉴权：`Authorization: Bearer <token>`（CredentialManager + RBAC）。

## 实现

JDK `com.sun.net.httpserver.HttpServer` + 4 线程池；端口 0（随机）可测试。

## 基准

HTTP 往返见集成测试（进程内）。

## 限制

- 轻量实现，TLS/限流由部署层提供；
- UI/自服务门户为 Phase 33。
