# ADR-0006: RESP Protocol Version

## Status

Accepted

## Context

Tiering-KV 需兼容 Redis 协议，供 redis-cli / redis-benchmark 与主流客户端直接使用。
协议需支持：命令请求与多种响应类型（简单字符串/错误/整数/bulk/数组/空值）、
pipeline、二进制安全键值。RESP3 引入 push / map / set / double / big number 等类型，
主要服务于发布订阅与客户端缓存场景，不在当前 KV 需求内。

## Decision

1. **Phase 1 采用 RESP2** 作为线上协议：请求为 bulk string 数组，响应使用
   `+ - : $ *` 类型；GET 未命中返回 `$-1`（nil bulk）。
2. **兼容 inline 命令**：支持文本行命令（如 `PING\r\n`），按空白分词；引号语法
   暂不支持（记录为已知限制）。
3. **协议模块抽象**：`RespValue` 类型体系 + Decoder/Encoder 独立于命令与网络层，
   未来如需 RESP3 以新 ADR 引入。
4. **执行模型（Phase 1）**：命令在连接的事件循环内同步执行（类 Redis 单连接串行
   语义）；key 分片工作线程池按 ADR-0003 推迟到 Phase 7。
5. **防护**：行/元素长度上限（64KiB）、bulk 上限（512MiB）、数组元素上限（1Mi）；
   协议错误返回 `-ERR Protocol error: ...` 后关闭连接。

## Alternatives

1. RESP3：类型更丰富，但当前命令集完全不需要，且部分客户端兼容性不足。
2. 自研二进制协议：性能可控，但破坏生态兼容，与项目目标冲突。
3. 直接引入 Redis 官方协议库：违背"从零自研"约束。

## Consequences

**优点：** redis-cli / redis-benchmark 直接兼容；实现简单、可增量演进。
**缺点：** RESP2 无 push 语义，后续 pub/sub 需升级 RESP3。
**风险：** inline 引号解析缺失导致个别客户端失败 → 记录为已知限制，按需补全。

## Implementation

- `io.tieringkv.protocol`：RespValue 类型体系、RespDecoder、RespEncoder；
- `io.tieringkv.command`：RespCommand、RespRequestParser、命令注册表与执行引擎；
- `io.tieringkv.network.*`：Netty 管道接入；
- Phase 1 命令集：PING / ECHO / SET / GET / DEL / EXISTS。
