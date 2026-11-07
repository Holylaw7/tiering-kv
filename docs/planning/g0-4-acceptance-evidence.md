# G0-4 验收证据：备份容量保护与预留

- 设计：`docs/adr/ADR-0369-backup-capacity-protection.md`
- 环境：Windows 11 / JDK 21.0.12 / MySQL 8.0.39 / Tiering-KV 5.1.0

## 1. 真实 MySQL 迁移（V5）

| 项 | 结果 |
| --- | --- |
| `flyway_schema_history` | 新增 **5 backup capacity**（success=1） |
| 表 | `backup_capacity`（锁锚点）、`backup_reservation`（预留）均创建 |

## 2. 正常导出：预留 → 写入 → 释放

| 项 | 实测 |
| --- | --- |
| `GET /backups/capacity` | 200，返回 usable=182,448,005,120 B、reserved=0、minFree=512 MB、floor=16 MB |
| 导出 | 20,570 键，4,504,062 B |
| 预留记录 | `reserved_bytes=16,777,216`（下限）→ `used_bytes=4,504,062` → **RELEASED** |
| 临时文件 | 备份目录 `*.tmp` 数量 **0**（原子发布后无残留） |

## 3. 空间不足：导出前拒绝（507）

以 `min-free-bytes = 182,443,479,040`（约等于当前可用空间）启动实例 C，请求导出：

```text
capacity: usable=182,434,398,208  minFree=182,443,479,040  reserved=0
create http=507
{"code":50700,"message":"备份空间不足：可用 173983 MB，已预留 0 MB，需保留 173991 MB，本次需要 100 MB"}
```

拒绝发生在导出开始之前：目标实例本身处于 DOWN 状态，若先走 SCAN 会得到上游错误而非 507。

## 4. 并发预留（部分验证，见 `5）

尝试让 A、B 两个控制面实例对同一存储路径并发导出：

```text
A http=200  (id=100, RUNNING)
B http=409  {"message":"该实例已有备份任务在执行（id=100），请稍后再试"}
```

同一实例的并发被 G0-1 的"备份进行中"守卫先拦下，**容量竞争未被端到端触发**。
跨实例的预留求和逻辑由两点保证：① `backup_capacity` 行级悲观锁串行化判定；
② 单元测试覆盖"已有预留占用可用空间"。真正的双实例容量竞争 E2E 仍需在可控磁盘上补做。

## 5. 自动化测试

| 测试 | 覆盖 |
| --- | --- |
| `BackupCapacityServiceTest`（7 例） | 预留成功、保留余量不足拒绝（507）、已有预留占用、释放幂等、估算（历史×系数）、下限估算、容量视图 |
| 控制面全量 | **124 例，0 失败**（117 + 7） |

## 6. 未覆盖（不得默认已解决）

| 项 | 状态 |
| --- | --- |
| 真实磁盘耗尽（ENOSPC） | **未验证**：Windows 无可靠受限文件系统；代码路径为临时文件 + 失败清理 + 记录 FAILED，但未在真实满盘上执行 |
| 只读文件系统 | 未验证 |
| 双实例容量竞争 E2E | 未触发（被备份并发守卫先拦） |
| 预留超时回收的实际触发 | 仅单测/代码路径，未等待 30 分钟 |
| 进程崩溃留下的 `.tmp` | 依赖 G0-2 孤儿清理，未做崩溃注入 |

## 7. 复现方式

```powershell
. .\scripts\dev-mysql.ps1
java -cp target/tiering-kv-5.1.0-shaded.jar io.tieringkv.Main
# 正常
java -jar console-server/target/tiering-kv-console-5.1.0.jar --server.port=8080
# 空间不足（把 min-free 设成接近可用空间）
java -jar ... --server.port=8082 --console.backup.capacity.min-free-bytes=<接近 usable> --console.backup.capacity.estimate-floor-bytes=104857600
node console-web/scripts/g04-capacity-check.mjs
node console-web/scripts/g04-insufficient.mjs
```
