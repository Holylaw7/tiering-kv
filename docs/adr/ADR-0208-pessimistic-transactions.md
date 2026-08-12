# ADR-0208: Pessimistic Transactions

## Status

Accepted

## Context

现有 Percolator 2PC 为乐观路径（提交时冲突检测）；高竞争场景需要
悲观路径（提前加锁）。

## Decision

1. `transaction/pessimistic/PessimisticTransaction`：BEGIN → 提前
   Lock → 已锁键可见性 → COMMIT/ROLLBACK；
2. 与 LockTable / TransactionParticipant 联动；
3. 死锁超时；
4. 不破坏现有乐观 2PC 语义；
5. 验收：锁冲突矩阵 + 读写可见性 + 死锁超时。

## Alternatives

1. 仅乐观：高竞争重试成本高；
2. 全悲观：低竞争锁开销。

## Consequences

优点：高竞争场景冲突提前暴露。

缺点：锁持有成本。

风险：死锁由超时兜底。

## Implementation

代码影响范围：`transaction/pessimistic/` + 测试 +
`docs/transaction/pessimistic.md`。
