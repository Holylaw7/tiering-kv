# ADR-0051: RPC Security Upgrade

## Status

Accepted

## Context

Phase 13 的 RPC 使用静态 token（无签名、可重放、不可轮换）。生产需要
防重放、可轮换的认证与双向证书校验。

## Problem

- 需要 HMAC-SHA256 签名（clientId + timestamp + nonce + signature）；
- 需要防重放（nonce 缓存）与过期校验；
- 需要密钥轮换（双密钥窗口）；
- 需要 mTLS（ONE_WAY / MUTUAL）。

## Options

1. **静态 token（现状）**：无法防重放/轮换；
2. **HMAC 认证 + mTLS（选定）**：应用层签名防重放，传输层双向证书；
3. **OAuth/JWT**：引入外部依赖，过重。

## Decision

采用 **HMAC-SHA256 + mTLS**：

```text
token = clientId | timestamp | nonce | HMAC-SHA256(clientId|timestamp|nonce, key)
服务端校验：签名 → 时间窗口（±30s）→ nonce 防重放缓存 → 轮换密钥表
```

1. `rpc.tls.mode=ONE_WAY|MUTUAL`：MUTUAL 时服务端要求并校验客户端证书
   （CA 链）；
2. 轮换：配置双密钥（active/previous），签发的 token 用 active，
   校验时任一匹配；
3. AUTH 帧 payload 升级为签名 token 格式。

## Consequences

**优点：** 防重放、可轮换、双向证书；
**缺点：** nonce 缓存与时钟窗口需运维注意；
**风险：** 时钟偏差 → 窗口可配置。

## Implementation

- `io.tieringkv.cluster.rpc.security`：HmacToken / NonceCache /
  KeyRotation；RpcAuthInterceptor 升级；
- RpcSecurityConfig 增加 tlsMode / clientCert / ca / keys。
