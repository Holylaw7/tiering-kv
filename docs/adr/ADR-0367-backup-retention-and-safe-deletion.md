# ADR-0367：备份保留策略与安全删除

- 状态：Accepted
- 日期：2026-11-07
- 关联：ADR-0365（恢复语义）、ADR-0366（恢复互斥）、G0-2

## 背景

当前 `BackupService.delete(id)` 只做两件事：删文件（异常仅告警）、删数据库记录。
由此产生三个生产问题：

1. **无保留策略**：备份目录只增不减，直到磁盘写满；
2. **无状态保护**：RUNNING 的备份、正在被恢复的备份都能被删除；
3. **数据库与文件非原子**：先删文件再删记录，文件删除失败会留下"记录指向不存在文件"的坏状态；
   反过来若先删记录，则留下孤儿文件，且无人清理。

## 决策

### 1. 保留语义：只对"成功的备份"计数

- 每个实例保留最近 **N 个 SUCCESS 备份**（`db_instance.backup_retention` 可覆盖，缺省取
  `console.backup.retention.default-count`，默认 10）；
- 永远保留**最近一个 SUCCESS**（即使 N=0），保证实例至少有可恢复点；
- FAILED 记录按时间清理（`failed-retention-days`，默认 7 天），不占用 SUCCESS 名额；
- RUNNING、RESTORING 一律不删。

### 2. 删除协议：先认领、再删文件、最后删记录

```text
① 认领（数据库，原子）
   UPDATE backup_record SET delete_state=PENDING, delete_claimed_at=NOW()
   WHERE id=? AND delete_state IS NULL AND status=SUCCESS
     AND restore_status<>RESTORING
   影响行数=1 才表示本进程获得删除权
② 删文件（幂等：文件不存在视为完成）
③ 删记录
```

任一步失败的处理：

| 失败点 | 结果 | 恢复方式 |
| --- | --- | --- |
| ① 未认领（影响行数 0） | 跳过 | 其他实例/进程正在处理 |
| ② 文件删除失败 | 记录保留在 PENDING | 下轮清理重试（超过 grace 可重新认领） |
| ③ 记录删除失败 | 文件已删、记录仍在 | 下轮重试，文件不存在视为已完成 |
| 进程在 ②③ 之间崩溃 | 记录 PENDING + 无文件 | 下轮重试即可收敛 |

### 3. 多实例协调用数据库，不用 JVM 锁

认领是一条带条件的 UPDATE，依赖数据库行锁与条件判断，因此两个控制面实例同时清理时，
只有一方的影响行数为 1。JVM 锁只用于本进程内避免重复调度。

### 4. 孤儿文件

文件存在但没有对应记录（进程在"写文件"与"落库"之间崩溃）时，由同一清理任务回收，
但只删除**修改时间早于 grace（默认 60 分钟）**的文件，避免误删正在写入的备份。

### 5. 可观测性

`POST /api/v1/backups/purge`（ADMIN）触发一次清理并返回报告；
`GET /api/v1/backups/retention`（ADMIN）返回上次报告与当前配置；
报告字段：实例数、候选数、删除数、失败数、跳过数、孤儿文件数、开始/结束时间。
定时任务每小时执行一次。

## 后果

- 保留策略只约束"备份数量"，**不保证磁盘占用**：不同时间的备份大小差异很大，
  磁盘水位仍需 G0-4 单独处理；
- `DELETE /api/v1/backups/{id}` 复用同一协议：RUNNING/RESTORING 返回 409，
  删除前必须认领成功；
- 恢复中的文件受保护，但"恢复开始后才被清理"的竞态依赖 RESTORING 状态检查，
  该竞态由测试覆盖。

## 验证计划

| 场景 | 断言 |
| --- | --- |
| 超过保留数量 | 只删最旧的 SUCCESS，保留 N 个 |
| RUNNING / RESTORING | 不被清理 |
| 唯一可恢复备份 | N=0 时仍保留最近一个 |
| FAILED 记录 | 超过天数才清理 |
| 文件删除失败 | 记录保留 PENDING，下一轮重试成功 |
| 孤儿文件 | 超过 grace 才删除 |
| 认领失败 | 跳过，不重复删除 |
| 新建备份并发 | 新备份（RUNNING 或最新 SUCCESS）不被误删 |
| 多实例 | 认领使用数据库条件更新 |
## 验证结果（2026-11-07，真实 MySQL 8.0.39 + 两实例）

| 场景 | 实测 |
| --- | --- |
| V4 迁移 | 四列 + 索引应用成功，Flyway 版本 4 |
| 保留 2 个 | 6 条 SUCCESS → 清理后 2 条 |
| RESTORING 保护 | 清理 deleted=5、skipped=1，受保护记录仍在 |
| 孤儿文件 | 2 小时前的无主文件被删除（removed=1） |
| 两实例并发清理 | A deleted=4、B deleted=0，最终 2 条，无重复删除 |

**真实环境暴露的两个问题**（单测未能发现，已修复）：

1. JPQL 中枚举参数与列值比较在 MySQL 上未生效，导致 RESTORING 记录被删；
   已改为原生 SQL，直接比较字符串；
2. `@Modifying` 查询缺少事务导致 500，已补 `@Transactional`。

证据：`docs/planning/g0-2-acceptance-evidence.md`；测试：`BackupRetentionServiceTest`（10 例）。
