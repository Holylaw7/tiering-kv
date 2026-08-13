# ADR-0237: Cross-Cloud TSO Arbitration & Clock Rollback Protection

## Status

Accepted

## Context

Phase 45 的 `GlobalTsoClock` 提供多源中位数校准，但无跨云仲裁与回拨
保护。Phase 46 需要多数云时间共识，并防止时钟回拨破坏单调性。

## Decision

新增 `CrossCloudTsoArbitration`：

- 跨云授时仲裁：多数云时间源共识（中位数 + 容差过滤）；
- 回拨保护：单调计数器 + 最大回拨窗口（超过窗口触发告警/冻结）；
- 与 GlobalTsoClock / TsoDisasterRecovery / resolved-ts / 事务协调器
  联动；
- 恢复不回退复用 TsoService.restore 语义。

## Alternatives

1. 单云授时：跨云故障域不可控；
2. 无回拨保护：单调性可能被时钟回拨破坏；
3. 全量同步：依赖外部时间服务。

## Consequences

优点：跨云可比较时间戳 + 单调性 + 回拨告警。

缺点：多源仲裁计算开销（毫秒级缓存可接受）。

风险：多数云授时集体异常 → 冻结 + 告警，语义保持。

## Implementation

`transaction/tso/CrossCloudTsoArbitration` +
`src/test/java/io/tieringkv/transaction/tso/CrossCloudTsoArbitrationTest`、
`docs/transaction/cross-cloud-tso-arbitration.md`。
