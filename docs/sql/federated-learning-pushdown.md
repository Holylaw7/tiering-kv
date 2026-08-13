# Federated Learning Multi-Agent Pushdown

## 设计

联邦学习下推（ADR-0257）把 Phase 48 的加权 Q 聚合升级为隐私保护联邦
学习：

```text
本地智能体 Q 更新（奖励先裁剪）
    ↓
FedAvg 模型聚合（按权重平均）
    ↓
聚合后注入拉普拉斯噪声
    ↓
联邦决策（PUSHDOWN / KEEP_LOCAL）
```

## 隐私保护

- 梯度裁剪：奖励在进入 Q 更新前裁剪到 `[-clipBound, clipBound]`，
  限制单次更新影响；
- 噪声注入：聚合模型每个动作值注入 `[-noiseScale, noiseScale]`
  均匀噪声；
- 隐私统计：`PrivacyStats` 暴露裁剪次数 / 噪声次数 / 语义一致性检查数。

## 语义一致性

联邦学习只改变"下推还是本地执行"的决策，不改变 SQL 执行结果；
`checkSemantics(queryType, same)` 累计一致性检查，测试矩阵验证上层
SQL 结果不变。

## 接入点

`io.tieringkv.sql.coprocessor.FederatedPushdownLearning`，与
`MultiAgentPushdownCoordinator` / `ReinforcementPushdownAgent` /
`SqlExecutor` 联动；测试见 `FederatedPushdownLearningTest`
（收敛矩阵 / 隐私矩阵 / 语义矩阵 / 校验矩阵）。
