# Upgrade & Backup Drills

## 滚动升级演练

`./scripts/upgrade-drill.sh <node-list-file>`：

1. 逐节点升级（旧 → 新版本）；
2. 追平等待（复制滞后归零）；
3. 数据奇偶校验（sha256sum 对比）。

## 备份恢复演练

`./scripts/restore-drill.sh <backup-dir>`：

1. 快照恢复；
2. WAL 重放；
3. MVCC 索引重建；
4. 恢复后数据校验。

真实多机演练待 Linux Runner（TD-048/049 封板项）。
