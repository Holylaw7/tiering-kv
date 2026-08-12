# 签名密钥轮换指南（ADR-0202）

## 流程

```text
prepareNext(k2) → rotate() → k2 生效，k1 进入宽限期
验证：active 或最近退休密钥均可
rollback()：退回最近退休密钥
```

## 使用

```java
KeyRotationManager manager = new KeyRotationManager(activeKey);
manager.prepareNext(nextKey);
manager.rotate(now);
manager.validates(oldKey); // 宽限期 true
manager.rollback();
```

轮换原子、可回滚、历史证明不中断。
