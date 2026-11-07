# G0-1 验收证据：恢复互斥与恢复状态机

- 代码提交：`238b555`（feat(console): G0-1 恢复互斥与恢复状态机）
- 证据提交：本轮提交（脚本 + 本文档）
- 执行时间：2026-11-07　执行人：项目作者（AI 辅助执行脚本）
- 环境：Windows 11 / Microsoft OpenJDK 21.0.12 / MySQL 8.0.39 / Tiering-KV 5.1.0

## 1. 真实 MySQL 迁移验证

### 1.1 迁移前

数据库：`jdbc:mysql://127.0.0.1:3307/tiering_console`（专用实例，数据目录 `%LOCALAPPDATA%\tieringkv-mysql`）。

```text
flyway_schema_history: 1 init(success=1), 2 seed default instance(success=1)
backup_record 列: id, instance_id, file_name, size_bytes, key_count, status, message, created_at, finished_at
```

### 1.2 迁移执行

启动控制面实例 A（8080），Flyway 自动应用 V3：

```text
flyway_schema_history: 1 init / 2 seed default instance / 3 backup restore tracking（success=1）
```

### 1.3 迁移后结构（`SHOW COLUMNS` / `SHOW INDEX` 实测）

| 列 | 类型 | 允许空 | 默认值 | 符合预期 |
| --- | --- | --- | --- | --- |
| restore_status | varchar(16) | NO | NONE | ✅ |
| restore_started_at | datetime(3) | YES | NULL | ✅ |
| restore_finished_at | datetime(3) | YES | NULL | ✅ |
| restored_keys | bigint | NO | 0 | ✅ |
| restore_message | varchar(255) | YES | NULL | ✅ |
| 索引 idx_backup_record_instance_restore | (instance_id, restore_status) BTREE | — | — | ✅ |

### 1.4 重复执行行为

实例 B（8081）在 V3 已应用后启动，Flyway 日志：

```text
Current version of schema `tiering_console`: 3
Schema `tiering_console` is up to date. No migration necessary.
```

`flyway_schema_history` 中 V3 仍只有一行，未重复执行。

## 2. 真实控制面并发恢复（跨实例）

### 2.1 拓扑

| 组件 | 配置 |
| --- | --- |
| 内核 | 5.1.0，6379，`java -cp tiering-kv-5.1.0-shaded.jar io.tieringkv.Main` |
| 控制面 A | 8080，`java -jar tiering-kv-console-5.1.0.jar --server.port=8080` |
| 控制面 B | 8081，同 jar，同一 MySQL |
| 数据 | 20,520 个键（`g01:key:*`） |

### 2.2 时间线

| 时刻（UTC） | 事件 | 结果 |
| --- | --- | --- |
| 12:11:57 | 创建备份（id=37） | 202/RUNNING |
| 12:12:00 | 备份完成 | SUCCESS，keyCount=20,520，4,501,182 B |
| 12:12:00.9 | A 发起恢复 | — |
| 12:12:00.9 | B 同时发起同一备份恢复 | **409**「该实例正在恢复（备份 id=37），请稍后再试」 |
| 12:12:03.7 | A 恢复完成 | **200**，耗时 2,857 ms |
| 12:12:03.7 | 记录状态 | restoreStatus=SUCCEEDED，restoredKeys=20,520，status=SUCCESS |
| 12:12:04.7 | 数据核对 | `DBSIZE=20520`；`GET g01:key:19999` → `v19999` |

> 关键点：两个请求打在不同控制面进程上，互斥依据是数据库里的 `RESTORING` 状态，
> 因此该结果同时证明了"跨控制面实例互斥"，而不只是单进程路径有效。

## 3. 故障场景

### 3.1 备份文件中途损坏

在备份文件第 100 行后插入非法 JSON，再次恢复：

| 项 | 实测 |
| --- | --- |
| HTTP | **502** |
| 消息 | 恢复失败（已回放 100 个键，数据可能不完整）：Unexpected character … |
| restoreStatus | FAILED |
| restoredKeys | 100 |
| backup status | **SUCCESS（未被污染）** |
| 文件修复后重试 | **200**，SUCCEEDED，restoredKeys=20,520 |

### 3.2 僵尸 RESTORING 回收

新建备份（id=38）后直接改库：`restore_status=RESTORING`、`restore_started_at = NOW() - 45 分钟`，
随即恢复 id=37：

| 项 | 实测 |
| --- | --- |
| 恢复请求 | **200**（未被僵尸状态阻塞） |
| 僵尸记录 | restore_status=**FAILED**，restore_message="恢复超时未完成，已标记为失败" |

### 3.3 恢复中导出（跨实例反向）

A 恢复进行中，B 发起同一实例备份：

| 项 | 实测 |
| --- | --- |
| 恢复 | 200 |
| 导出 | **409**「该实例正在恢复（备份 id=37），请稍后再试」 |

## 4. 与单测的分工

| 层次 | 证明什么 | 证据 |
| --- | --- | --- |
| 单元测试（6 例） | 状态机不变量、异常安全、可重试性；**移除守卫后并发用例实测失败** | `BackupServiceRestoreTest` |
| 真实 MySQL | V3 迁移的列/索引/默认值正确，重复启动不重复执行 | 本文 §1 |
| 真实控制面 E2E | 两个进程竞争同一数据库记录时仍只有一个获得执行权 | 本文 §2 |
| 故障注入 | 损坏文件与僵尸状态的终态正确、可重试 | 本文 §3 |

## 5. 仍未覆盖（不得默认已解决）

| 项 | 状态 |
| --- | --- |
| 恢复期间客户端直连内核 `SET` | **未隔离**：控制面不拦截直连写，本 ADR 不承诺 |
| 恢复期间删除实例 | **未测试**：实例删除与恢复的竞态仍存在 |
| 多控制面实例的时钟差异 | 未测：僵尸判定用本机 `LocalDateTime` |
| 大备份（>20 万键） | 未测：导出有 `MAX_KEYS=200_000` 限制，恢复无上限 |
| 恢复性能 | 20,520 键耗时 2,857 ms（约 7,180 键/秒），未做容量基线 |

## 6. 复现方式

```powershell
. .\scripts\dev-mysql.ps1                  # 3307
java -cp target/tiering-kv-5.1.0-shaded.jar io.tieringkv.Main        # 6379
java -jar console-server/target/tiering-kv-console-5.1.0.jar --server.port=8080
java -jar console-server/target/tiering-kv-console-5.1.0.jar --server.port=8081
node console-web/scripts/g01-e2e-restore.mjs    # 并发恢复
node console-web/scripts/g01-e2e-failure.mjs    # 损坏文件 + 僵尸状态
node console-web/scripts/g01-e2e-crossop.mjs    # 恢复 vs 导出
```

> 注：脚本假设 `admin/admin123` 与实例 id=1（`local-6379`），与 `V2__seed_default_instance.sql` 一致。
