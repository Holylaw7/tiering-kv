# 悲观事务指南（ADR-0208）

## 使用

```java
PessimisticTransaction txn = new PessimisticTransaction(500);
txn.begin("t1");
boolean acquired = txn.lock("k1", "t1", now, acquiredAt);
txn.write("k1", value);
byte[] value = txn.read("k1");
txn.commit(); // 或 rollback()
```

提前加锁暴露冲突；死锁超时由 lockTimeoutMillis 兜底；
不破坏现有乐观 2PC 语义。
