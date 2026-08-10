# 跨机部署指南（Cross-Machine Guide）

Phase 14 · 2026-08-10

## 1. 最小拓扑

| 角色 | 数量 | 说明 |
| --- | --- | --- |
| storage-node | 3 | 数据分片 Raft 组 |
| metadata-node | 3 | 元数据 Raft 组（持久化） |
| gateway-node | 1 | 客户端入口 + 路由 |

## 2. 端口

| 端口 | 用途 |
| --- | --- |
| 6379 | 客户端（gateway） |
| 7000 | 数据 Raft RPC |
| 7001 | 元数据 Raft RPC |
| 7002 | 管理 RPC（预留） |

## 3. 配置示例

```yaml
cluster:
  nodeId: storage-1
  role: storage-node
  network:
    peers:
      - storage-1@10.0.0.1:7000
      - storage-2@10.0.0.2:7000
      - storage-3@10.0.0.3:7000
  security:
    tls: true
    tlsMode: MUTUAL
    hmac: true
metadata:
  endpoints:
    - 10.0.0.1:7001
    - 10.0.0.2:7001
    - 10.0.0.3:7001
```

## 4. 部署步骤

1. 生成 CA/服务端/客户端证书并分发；
2. 配置 HMAC 密钥（环境变量注入，支持双密钥轮换）；
3. 启动 3 个 metadata-node（持久化目录独立）；
4. 启动 3 个 storage-node；
5. gateway 配置 metadata endpoints；
6. 验证：`PING`、`SET/GET`、杀 leader 后选举与重路由、元数据重启拓扑保留。

## 5. 验证清单

- [ ] 数据写入在 3 副本可见；
- [ ] 杀 storage leader → 新 leader 选举（<5s）→ 读正确；
- [ ] 杀 metadata leader → 元数据仍可写；
- [ ] metadata 全组重启 → 拓扑保留；
- [ ] TLS/HMAC 互认通过。
