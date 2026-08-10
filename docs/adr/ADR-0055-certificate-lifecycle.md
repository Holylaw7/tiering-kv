# ADR-0055: Certificate Lifecycle

## Status

Accepted

## Context

Phase 14 mTLS 使用静态 PEM 文件，证书过期/轮换需重启进程，不满足
生产要求。

## Problem

- 需要 load / validate / expire detection / reload / rotation；
- 轮换需 old → validate new → atomic switch，不中断连接。

## Options

1. **静态加载（现状）**：过期即中断；
2. **CertificateManager + Watcher（选定）**：文件监听 + 原子切换；
3. **外部 CA 服务**：超出范围。

## Decision

采用 **CertificateManager**：

```text
load(证书/密钥/CA) → validate（有效期/CA 链）→ 周期 expire 检测
  → CertificateWatcher（WatchService 监听文件变化）→ reload → 原子切换
```

1. 切换仅影响新连接（SslContext 引用原子替换），已有连接不中断；
2. 轮换支持 old/new 双证书窗口。

## Consequences

**优点：** 证书过期可预警、轮换免重启；
**缺点：** WatchService 平台差异需测试；
**风险：** 证书写入非原子 → 临时文件 + 原子 rename。

## Implementation

- `io.tieringkv.cluster.rpc.security`：CertificateManager /
  CertificateWatcher / CertificateInfo。
