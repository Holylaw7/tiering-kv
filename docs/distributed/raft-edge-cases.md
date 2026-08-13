# Raft Edge Cases

## 验证矩阵（只测不改）

- leader 选举与重复选举（5 轮矩阵）；
- 写复制到全部节点；顺序写保持；
- leader 崩溃故障转移（suspend + close → 新 leader 可写）；
- 滞后 follower 恢复后追平；
- 少数派节点丢失后多数派继续提交（3/5 节点矩阵）。

发现缺陷必须走 ADR 修复流程；真实网络分区待 Runner。
