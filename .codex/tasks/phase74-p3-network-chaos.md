# Phase 74 — P3：真实网络 netem 混沌

## Context

P3 第二项。基线：现有 partition 因镜像缺 tc + `|| true` 静默吞错，
极可能从未生效；需要可验证的真实 netem 闭环。

## Goal

1. ADR-0343 已批准（本阶段）
2. Dockerfile 安装 iproute2（tc）
3. network-chaos.sh：delay/loss/partition/recover/show + 应用后校验
4. RealNetworkChaosTest：真实 RESP 链路三阶段演练（门控）
5. CI container-e2e 接线 delay/loss/partition/recover
6. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| 镜像 | deploy/Dockerfile（iproute2） |
| 脚本 | scripts/network-chaos.sh |
| 客户端 | distributed/harness/RespClient.java（setTimeout，additive） |
| 演练 | runtime/RealNetworkChaosTest |
| CI | .github/workflows/transaction-e2e.yml |
| 文档 | ADR-0343、deployment/network-chaos.md、roadmap、CHANGELOG |

## Test Plan

- 本地：门控演练跳过；RespClient setTimeout 单测；全量回归 0 failures
- 真实 Runner：delay 100ms / loss 10% 演练成功；partition 演练断言
  失败；recover 后演练成功

## 验收

- ADR-0343 已批准；Conventional Commit 拆分
- container-e2e netem 全阶段真实执行（tc qdisc show 校验非空）
- 全量回归 0 failures；真实 Runner 门禁 6/6

## 真实 Runner 门禁发现（三次迭代）

1. `scripts/network-chaos.sh: Permission denied`：Windows 提交丢失
   可执行位（100644），`git update-index --chmod=+x` 修复。
2. `RTNETLINK answers: Operation not permitted`：容器缺 NET_ADMIN；
   compose 四后端服务 `cap_add: [NET_ADMIN]`。
3. `expected: 5 but was: 0`（0.22s 全失败）：根因是
   `GatewayRuntime` 行协议网关不解析 RESP 数组，`RespClient`
   每次收到 `-ERR unknown command`；改为 RESP2 数组解析 +
   标准编码（GatewayRuntimeRespTest 7 项），冒烟步骤改为读取并
   断言响应，演练失败时先抛底层 IOException（避免吞根因）。
4. 冒烟断言后 `Connection reset by peer`：根因是
   `CoordinatorRuntime.start` 的 RPC 地址表仅含自身，metadata/
   participant 的 `callTxn` 立即返回 `unknown peer`（此前冒烟
   只写不读从未触发真实事务路径）；地址表注册 metadata +
   全部 region host（createUnresolved），网关异常输出 stderr，
   冒烟改为有界重试吸收 Raft 就绪竞态。
5. 冒烟 10 次全失败但网关无错误：RESP 行尾是 `\r\n`，bash
   `read -r` 默认保留 `\r`，`+OK\r` ≠ `+OK`；读取改为
   `IFS=$'\r\n' read` 剥离 CR（已验证 `FIXED=[abc] len=3`）。

状态：修复链 4 轮（可执行位 → NET_ADMIN → RESP 合规 → RPC 地址表），
待真实 Runner 门禁最终通过后归档。
