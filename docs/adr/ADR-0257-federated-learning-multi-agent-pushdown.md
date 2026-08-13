# ADR-0257: Federated Learning Multi-Agent Pushdown

## Status

Accepted

## Context

Phase 48 多智能体 RL（ADR-0250）采用加权 Q 聚合。Phase 49 升级为隐私
保护联邦学习：本地 Q 更新 + FedAvg 模型聚合 + 噪声注入/梯度裁剪，
只改决策层，语义层与上层 SQL 结果保持一致。

## Decision

采用 `FederatedPushdownLearning`：

- 本地智能体 Q 表独立更新；中心聚合采用 FedAvg（按样本/权重平均）；
- 隐私保护：提交前梯度裁剪（clip），聚合后注入拉普拉斯噪声；
- 决策一致性：聚合模型只影响下推/本地执行选择，不改变 SQL 结果；
- 收敛跟踪 + 隐私预算统计（噪声量/裁剪比例）。

## Alternatives

1. 共享全部 Q 表：隐私泄漏，不满足多组织边界；
2. 无裁剪/无噪声 FedAvg：梯度信息可反推本地分布；
3. 同态加密/SMPC：正确但复杂度高，留 Phase 50+。

## Consequences

优点：隐私可证明（噪声注入 + 裁剪）、决策层可插拔、语义层零影响。

缺点：噪声降低收敛速度；聚合参数需要校准。

风险：隐私参数选择不当会失真，需通过测试矩阵校准。

## Implementation

`src/main/java/io/tieringkv/sql/coprocessor/FederatedPushdownLearning.java`
+ `src/test/java/io/tieringkv/sql/coprocessor/FederatedPushdownLearningTest.java`、
`docs/sql/federated-learning-pushdown.md`。
