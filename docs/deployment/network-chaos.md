# Real Network Chaos（tc netem，ADR-0343）

## 目标

在真实容器网络上应用/恢复 tc netem（延迟/丢包/全分区），并经真实
RESP 网关验证 Raft/事务 RPC 语义：抖动下最终一致、分区下不静默
成功、恢复后一致。

## 前置

- Linux + Docker；事务栈镜像含 iproute2（deploy/Dockerfile，
  ADR-0343 修正：此前镜像无 tc，container-chaos partition 静默 no-op）；
- 后端容器（txn-coordinator/participant-a/participant-b/txn-meta）需
  `NET_ADMIN` 能力（docker-compose.transaction.yml `cap_add`），否则
  `tc qdisc add` 返回 `Operation not permitted`（CI 已暴露并修复）；
- CI：GitHub Actions `transaction-e2e.yml` container-e2e job；
- 本地 Windows/macOS：演练测试自动跳过（OS/环境变量门控）。

## 脚本

```bash
scripts/network-chaos.sh delay 100ms   # 后端容器 eth0 加 100ms 延迟
scripts/network-chaos.sh loss 10%      # 10% 丢包
scripts/network-chaos.sh partition     # 100% 丢包（全分区）
scripts/network-chaos.sh recover       # 恢复 qdisc
scripts/network-chaos.sh show          # 查看 qdisc
```

应用后强制校验四个后端容器（txn-coordinator/participant-a/
participant-b/txn-meta）的 `tc qdisc show` 含 netem；任一未生效
即失败。gateway 不打 netem，保持宿主冒烟可达。

## 演练（RealNetworkChaosTest，门控）

| 阶段 | 断言 |
| --- | --- |
| delay 100ms | 5 轮 SET/GET 最终成功 |
| loss 10% | 5 轮 SET/GET 重试后最终成功 |
| partition | 有界时间（15s）内 SET 必须失败（不静默成功） |
| recover | 5 轮 SET/GET 恢复成功 |

运行（真实 Runner，compose 已启动）：

```bash
TIERINGKV_NETWORK_CHAOS=true TIERINGKV_NETEM_EXPECT=delay \
  mvn -q -Dtest=RealNetworkChaosTest#setGetRoundTripUnderNetem \
  -DfailIfNoTests=false test
```

## 已知限制

- netem 作用于后端容器 egress（单方向）；RPC 重试语义下等价于
  双向抖动；
- 分区演练依赖网关 RPC 超时（3s × 重试）返回错误/超时；
- 真实公网跨区域网络延迟/带宽抖动不在本范围（P3 后续）。
