# WATCH Version Guard

## 语义

- `AtomicStringOps.versionOf(key)`：段读锁返回 entry 版本（缺失 0）；
- WATCH 记录 key → version；UNWATCH 清空；
- EXEC 前校验全部被观察键版本：任一变化 → 返回 nil（abort），
  队列清空；
- 成功 EXEC 后观察集清空（Redis 语义）。

## 示例

```bash
WATCH k
SET k concurrent   # 并发修改
MULTI
SET k txn
EXEC               # -> nil（abort）
```
