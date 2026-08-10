# 分布式部署指南（Distributed Deployment）

Phase 13 · 2026-08-10

## 1. 节点角色

| 角色 | 职责 | 说明 |
| --- | --- | --- |
| gateway-node | 客户端连接 + 请求路由 + 拓扑缓存 | 无状态，可多实例 |
| metadata-node | 元数据 Raft 组：节点注册 / 分片拓扑 / slot 归属 / 迁移状态 | 3 节点一组 |
| storage-node | 数据分片 + Raft 复制 + 冷热分层 | 每分片 3 节点一组 |

## 2. 网络端口（示例）

| 端口 | 用途 |
| --- | --- |
| 6379 | Redis 客户端协议（gateway） |
| 7000 | 数据 Raft RPC（storage-node） |
| 7001 | 元数据 Raft RPC（metadata-node） |

## 3. 配置（YAML）

```yaml
cluster:
  nodeId: storage-1
  role: storage-node

raft:
  peers:
    - storage-1@10.0.0.1:7000
    - storage-2@10.0.0.2:7000
    - storage-3@10.0.0.3:7000
  replication:
    maxBatchEntries: 128
    maxBatchBytes: 1048576
    flushIntervalMillis: 5
    maxInflight: 8

metadata:
  endpoints:
    - 10.0.0.1:7001
    - 10.0.0.2:7001
    - 10.0.0.3:7001

rpc:
  ssl:
    enabled: true
    certFile: config/cert/server.crt
    keyFile: config/cert/server.key
  auth:
    token: ${RPC_AUTH_TOKEN}
  rate:
    limit:
      qps: 10000
```

## 4. 部署步骤

1. 每台机器生成/分发证书（`config/cert/`）与认证 token；
2. 启动 3 个 metadata-node（`metadata.role=metadata-node`）；
3. 启动 3 个 storage-node 组成分片 Raft 组；
4. gateway-node 配置 metadata endpoints，连接客户端；
5. 验证：`PING`、`SET`/`GET`、杀 leader 后选举与重路由。

## 5. 安全基线

- RPC 启用 TLS + token 认证 + 限流（ADR-0046）；
- token 通过环境变量注入，禁止明文入仓库；
- 生产建议 CA 签发证书与密钥轮换（当前原型为静态 PEM）。
