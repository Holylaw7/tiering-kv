# MULTI/EXEC Guide

## 语义

- MULTI → 后续命令返回 QUEUED 并进入连接队列；
- EXEC → 顺序执行队列，返回结果数组；
- DISCARD → 清空队列；嵌套 MULTI / 无 MULTI 的 EXEC/DISCARD 报错；
- WATCH → OK（无版本守卫，限制登记）。

## 限制

- EXEC 非整体原子：严格原子事务使用 MVCC 2PC 路径（Phase 19+）；
- 队列状态随连接生命周期清理。
