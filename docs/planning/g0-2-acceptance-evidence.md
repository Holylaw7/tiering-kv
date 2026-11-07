# G0-2 验收证据：备份保留策略与安全删除

- 设计：`docs/adr/ADR-0367-backup-retention-and-safe-deletion.md`
- 环境：Windows 11 / JDK 21.0.12 / MySQL 8.0.39 / Tiering-KV 5.1.0

## 1. 真实 MySQL 迁移（V4）

| 项 | 结果 |
| --- | --- |
| `flyway_schema_history` | 1 init / 2 seed / 3 restore tracking / **4 backup retention**（success=1） |
| `db_instance.backup_retention` | int NULL ✅ |
| `backup_record.delete_state` | varchar(16) NULL，索引 `idx_backup_record_delete_state` ✅ |
| `backup_record.delete_claimed_at` | datetime(3) NULL ✅ |

## 2. 保留语义（真实控制面，default-count=2）

| 步骤 | 结果 |
| --- | --- |
| 备份总数（实例 1，SUCCESS） | 6 |
| `POST /api/v1/backups/purge` | 200，candidates=4，deleted=4，failed=0 |
| 清理后 SUCCESS 记录 | **2**（保留最近两个） |
| `GET /api/v1/backups/retention` | 200，defaultCount=2，含上次报告 |

## 3. 保护规则（真实库注入）

把一条超出保留窗口的成功备份改为 `restore_status=RESTORING`（同时写入 `restore_started_at=NOW()`），再清理：

```text
protected id=89
purge deleted=5 skipped=1
protected row after purge: [89  SUCCESS  RESTORING]
```

结论：**正在恢复的备份不会被清理**，且该记录被计入 `skipped`。

> 第一次尝试只改 `restore_status` 而未改 `restore_started_at`，该记录被 G0-1 的僵尸回收逻辑
> 在后续导出时标记为 FAILED 并清理——这是设计行为，不是缺陷。测试夹具已修正。

## 4. 孤儿文件回收

在备份目录写入一个修改时间为 2 小时前的无主文件，触发清理：

```text
orphan purge: http=200 removed=1 exists=false
```

## 5. 两实例并发清理

实例 A（8080）与 B（8081）共用同一 MySQL，同时 `POST /backups/purge`：

```text
before=2  created=[94,95,96,97]
purgeA: deleted=4, skipped=0
purgeB: deleted=0, skipped=0
after=2
```

结论：删除权由数据库条件更新认领，另一实例不会重复删除；最终保留数量正确。

## 6. 本轮真实环境发现的问题（单元测试未能发现）

| 问题 | 现象 | 根因 | 修复 |
| --- | --- | --- | --- |
| JPQL 枚举参数 | `restore_status <> :restoring` 在真实 MySQL 上未生效，RESTORING 记录被删除 | 枚举参数绑定方式与列存储不一致 | 改为**原生 SQL**，直接比较字符串 |
| 缺少事务 | `POST /purge` 返回 500 | `@Modifying` 查询需要事务 | `@Transactional` 标注 purge()/delete() |

> 这两条说明：单测里 mock 掉的仓储层语义，必须在真实库上验证一次。

## 7. 未覆盖（不得默认已解决）

| 项 | 状态 |
| --- | --- |
| 删除过程中进程崩溃（文件已删、记录未删） | 逻辑上有收尾重试，未做崩溃注入 |
| 文件删除权限不足（只读/占用） | 未测 |
| 数据库删除失败后的下一轮收敛 | 单测覆盖（幂等 + existsById 守卫），真实库未注入 |
| 磁盘水位 | 不属于本项（G0-4） |
| 定时任务实际触发 | 未等待 1 小时；仅验证手工触发 |

## 8. 复现方式

```powershell
. .\scripts\dev-mysql.ps1
java -cp target/tiering-kv-5.1.0-shaded.jar io.tieringkv.Main
java -jar console-server/target/tiering-kv-console-5.1.0.jar --server.port=8080 --console.backup.retention.default-count=2
java -jar console-server/target/tiering-kv-console-5.1.0.jar --server.port=8081 --console.backup.retention.default-count=2
node console-web/scripts/g02-retention-e2e.mjs
node console-web/scripts/g02-protected-check.mjs
node console-web/scripts/g02-concurrent-purge.mjs
```
