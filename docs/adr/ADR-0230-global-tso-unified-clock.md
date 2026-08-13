# ADR-0230: Global TSO Unified Clock

## Status

Accepted

## Context

Phase 44 的 `TsoDisasterRecovery` 是主备模型，依赖单一时间源。Phase 45
需要全球统一时钟：混合授时源（GPS/原子钟/NTP）抽象 + 校准 + 单调，
并保持容灾联动。

## Decision

新增 `GlobalTsoClock`：

- 授时源抽象 `TimeSource`（GPS / ATOMIC / NTP / SIMULATED）；
- 混合校准：多源时间差 → 中位数校准 + 偏移上限；
- 单调推进：本地单调计数器与授时源差值取最大，绝不回拨；
- 与 TsoDisasterRecovery 联动：切换后以已同步水位继续分配；
- 恢复不回退复用 TsoService.restore 语义。

## Alternatives

1. 单一 NTP：跨地域漂移不可控；
2. 完全依赖硬件时钟：不可移植；
3. 无校准：单调但偏差累积。

## Consequences

优点：全球可比较时间戳 + 单调性；多源容错。

缺点：多源校准计算开销（毫秒级缓存可接受）。

风险：授时源集体故障 → 回退单调计数器，语义保持。

## Implementation

`transaction/tso/GlobalTsoClock` +
`src/test/java/io/tieringkv/transaction/tso/GlobalTsoClockTest`、
`docs/transaction/global-tso-unified-clock.md`。
