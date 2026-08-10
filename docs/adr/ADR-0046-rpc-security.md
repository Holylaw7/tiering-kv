# ADR-0046: RPC Security

## Status

Accepted

## Context

Phase 12 的 RPC 为明文、无认证、无限流：任何能连上端口的进程都可读写
Raft 消息，生产环境不可接受。

## Problem

- 需要传输加密（TLS）；
- 需要节点间认证（token，支持过期）；
- 需要限流（防误连/风暴）；
- 不改变现有线协议主体。

## Options

1. **TLS + 双向证书**：最强，但证书管理复杂（原型采用单向 TLS + token）；
2. **TLS + Token 认证 + Token Bucket 限流（选定）**：加密传输 +
   应用层认证 + 简单限流；
3. **仅应用层加密**：实现成本高且易错，否决。

## Decision

采用 **Netty SslContext + RpcAuthInterceptor + TokenBucket**：

1. TLS：`rpc.ssl.enabled=true` 时服务端加载
   `config/cert/server.crt / server.key`，客户端 trustManager 校验；
   测试使用 Netty `SelfSignedCertificate`（生产为 PEM 文件）；
2. 认证：连接建立后客户端发送 `AUTH` 帧（token），服务端校验
   token 值与过期时间；未认证连接的其他帧被拒绝；
3. 限流：`TokenBucket`（容量 = QPS，匀速补充），超限返回
   `ERROR` 帧 `ERR RATE_LIMIT`；
4. 配置：`rpc.ssl.enabled / rpc.auth.token / rpc.auth.expiry /
   rpc.rate.limit.qps`。

## Consequences

**优点：** 加密 + 认证 + 限流三层防护，与现有帧协议兼容；
**缺点：** TLS 握手增加连接建立延迟（基准中量化）；
**风险：** 自签名证书管理、token 静态配置 → 生产建议 CA + 密钥轮换
（登记后续）。

## Future Evolution

- 双向 mTLS；
- token 签名/轮换（HMAC + 动态密钥）；
- 每连接 + 每节点两级限流。
