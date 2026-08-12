# SQL Write 2PC Coordinator 指南（ADR-0144）

## 职责

`SqlTxnCoordinatorAdapter` 将 SQL 写事务桥接到真实
`GeoTransactionCoordinator`，与原生 2PC 共享决策日志与恢复语义。

## 使用

```java
SqlTxnCoordinatorAdapter adapter =
        new SqlTxnCoordinatorAdapter(coordinator, credentials);
adapter.begin(token);
adapter.write(key, value, false);
boolean ok = adapter.commit();   // prewrite 失败返回 false
adapter.rollback();              // 丢弃未提交变更
int recovered = adapter.recover(); // 决策日志重放
```

## 语义

- 提交：decision log（COMMIT）先于跨地域 commit；
- 回滚：decision log（ROLLBACK）+ 各区域 rollback；
- 幂等：区域客户端 ALREADY 视为成功；
- 失败：prewrite 失败由协调器回滚，适配器清理会话并返回 false。

## 一致性

禁止旁路事务状态机；SQL 写与原生 2PC 必须语义等价。
