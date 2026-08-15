# Cross-Cluster Transaction Design（ADR-0339）

## 数据路径

```text
Client
  |
CrossClusterTxnCoordinator
  |  决策先行（CrossClusterDecisionLog，'CCDC' + CRC32C）
  |
  +-- PREPARE --> CrossClusterReplicationChannel.sendBatch
  |                  |
  |                  v
  |             CrossClusterTxnParticipant（暂存，不落盘）
  |                  |
  +-- COMMIT  --> 参与者按 LWW 决策应用（commitTS 收敛）
  |
  +-- ROLLBACK --> 参与者丢弃暂存
```

## 阶段事件

`ChangeEvent.EventType` 末尾追加 TXN_PREPARE / TXN_ROLLBACK
（既有 ordinal 0-3 冻结，线格式兼容）。事件携带：

- seq：事务序号（恢复重放同 seq，LWW 幂等）；
- regionId：目标集群 id；
- timestamp：PREPARE=startTS，COMMIT=commitTS；
- key/value/deleted：mutation。

## 参与者语义

- TXN_PREPARE：暂存（校验失败返回 false → ERROR 帧 → 协调器回滚）；
- TXN_COMMIT：暂存存在 → 逐 key 以 commitTS 构造事件经
  `ConflictResolver` 判定后落盘；无暂存（恢复重放）→ 直接判定应用；
- TXN_ROLLBACK：丢弃暂存；
- 冲突收敛：高 commitTS 胜，同时间戳按源 cluster id 字典序胜，
  同 (regionId, seq) 重放幂等。

## 协调器语义

1. 按 `clusterOf(key)` 分组；
2. 全部集群 PREPARE 成功 → 决策 COMMIT 落盘（携带 mutations）→
   全部 COMMIT；PREPARE 任一失败 → 决策 ROLLBACK + 全部 ROLLBACK；
3. COMMIT 阶段失败不覆盖决策（recover 重发补提交）；
4. `recover()`：对 COMMIT 决策按 mutations 重发 COMMIT（幂等）。

## 已知限制

- PREPARE 为暂存式（无锁），跨集群无事务级隔离（LWW 收敛）；
- 无 key → cluster 路由表（调用方注入 clusterOf）；
- 决策日志为单机文件（后续可接元数据 Raft）。
