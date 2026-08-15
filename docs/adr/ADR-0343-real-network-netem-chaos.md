# ADR-0343: Real Network Chaos (tc netem)

## Status

Accepted

## Context

P3 第二项：真实网络混沌。现状：container-chaos.sh 的 partition 用
`docker exec ... tc ... || true`，但运行时镜像（eclipse-temurin:17-jre）
**未安装 iproute2/tc**，且 `|| true` 静默吞错——"分区"极可能从未
真正生效。TD-035 已按既有证据关闭，但需要可验证的真实 netem 闭环。

## Decision

- **运行时镜像**：Dockerfile 安装 `iproute2`（tc），netem 注入不再
  依赖宿主工具；
- **network-chaos.sh**：对后端容器（txn-coordinator/participant-a/
  participant-b/txn-meta）eth0 应用/恢复 tc netem：
  `delay <ms>` / `loss <%>` / `partition`（loss 100%）/ `recover` /
  `show`；应用后强制校验 `tc qdisc show` 含 "netem"，未生效即失败
  （禁止静默降级）；gateway 不打 netem（保持宿主冒烟可达）；
- **NET_ADMIN 能力**：上述后端容器在 compose 中 `cap_add: [NET_ADMIN]`，
  否则容器内 `tc qdisc add` 报 `Operation not permitted`
  （真实 Runner 首次门禁暴露，已修复）；
- **网关 RESP 合规修正**：真实 Runner 门禁暴露
  `GatewayRuntime` 原本按行解析命令，`RespClient` 的 RESP 数组
  （`*3\r\n$3\r\nSET...`）被当成未知命令返回 `-ERR`；现改为
  RESP2 数组解析 + 标准响应编码（`+OK`/`$len`/`$-1`/`-ERR`），
  容器冒烟改为读取并断言响应（禁止只写不读的伪冒烟）；
- **RealNetworkChaosTest**（Linux + TIERINGKV_NETWORK_CHAOS 门控，
  本地跳过）：经 127.0.0.1:6379 走真实 RESP 链路：
  - `setGetRoundTripUnderNetem`（delay/loss/recovered 三阶段）：5 轮
    SET/GET 带重试，最终一致；
  - `partitionBlocksRoundTrip`（partition 阶段）：有界时间内 RPC
    失败/超时（不静默成功）；
- **CI container-e2e 接线**：delay 100ms → 演练成功；loss 10% →
  演练成功；partition → 演练断言失败；recover → 演练恢复成功；
- `RespClient.setTimeout`（additive）：为门控演练提供有界 I/O。

## Alternatives

1. 宿主 loopback netem：影响整个 CI 网络，风险高；
2. 保留静默 `|| true`：无真实证据。

## Consequences

优点：真实 tc netem 证据、镜像自包含、失败显式化、网关 RESP
合规（顺带修复 ADR-0093 网关行协议缺陷）。

缺点：镜像构建增加 apt 层（~1 分钟）；netem 作用于后端容器 egress
（单方向），RPC 重试语义下等价于双向抖动。

风险：GH Runner docker 网络命名空间差异——门禁 job 失败即真实阻塞。

## Implementation

`deploy/Dockerfile`（iproute2）、`docker-compose.transaction.yml`
（cap_add NET_ADMIN）、`scripts/network-chaos.sh`、
`runtime/RealNetworkChaosTest`、`RespClient.setTimeout`、
`runtime/GatewayRuntime`（RESP2 解析/编码）、
`transaction-e2e.yml` container-e2e 接线、部署文档。
