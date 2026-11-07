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

### 环境

- WSL2 Ubuntu 26.04，OpenJDK 25 运行同一 fat jar；
- 控制面运行在 WSL 内，数据源为 Windows 侧 MySQL 8.0.39（3308，绑定 0.0.0.0，已授予 `console_app@%`）；
- 内核运行在 WSL 内（127.0.0.1:6379），通过 bash /dev/tcp 写入 8,000 个 1 KB 键；
- 备份目录挂载在 `tmpfs size=2m`，**这是真实受限文件系统**，不是 Mock 抛异常。

### 结果

| 步骤 | 实测 |
| --- | --- |
| 首次导出（2 MB 卷，1.8 MB 数据） | SUCCESS，1,858,239 B（未触发上限） |
| 追加数据后再次导出 | **FAILED**，消息 **No space left on device** |
| 备份目录 | 清理后为空，**无 `.tmp` 残留** |
| 预留记录 | 该记录 `reserved=185,823 → used=0 → RELEASED` |
| 卷扩容到 64 MB 后重试 | **SUCCESS**，3,716,478 B / 3,438 键 |
| 既有 SUCCESS 记录 | 数据库中仍为 SUCCESS（其文件因测试重挂载被清空，属测试环境副作用，非应用行为） |

> 关键点：失败发生在**真实 write(2) 返回 ENOSPC** 时，而不是应用层预检；预检值为 185,823 B（最近成功备份 × 0.1），低于 2 MB，因此放行。

## 4B. 双实例并发预留——**已验证**（确定性设计）

### 拓扑

| 组件 | 配置 |
| --- | --- |
| MySQL | 8.0.39，3308，共享 `backup_capacity` / `backup_reservation` |
| 控制面 A | 8080，`--console.backup.capacity.min-free-bytes=<usable-150MB> --estimate-floor-bytes=104857600 --estimate-factor=1.0` |
| 控制面 B | 8081，同上参数，同一 MySQL |
| 内核 A | 6379，数据目录 `.tmp-k1/data` |
| 内核 B | 6383，数据目录 `.tmp-k2/data`（独立） |
| 备份目录 | 两个控制面指向**同一路径**（`console-server/backups`） |
| 数据量 | 实例 1：60,000 键（约 34 MB） |

### 设计（确定性，不依赖请求"碰巧同时到达"）

可用空间 = `usable - minFree` ≈ **150 MB**，单次估算 = **100 MB**（floor 覆盖历史大小）。
因此 A 预留成功后，B 若同时判定必然超出。A 的导出需要写出约 34 MB，预留在整个导出期间保持有效。

### 结果

| 步骤 | 实测 |
| --- | --- |
| A 导出（实例 1） | **200**，记录 id=6 RUNNING；预留 100 MB |
| B 同时导出（实例 2，另一进程） | **507**「可用 173916 MB，已预留 **100 MB**，需保留 173766 MB，本次需要 100 MB」 |
| B 的失败记录 | id=7 **FAILED**（不是 SUCCESS） |
| A 完成 | SUCCESS，60,000 键 / 34,188,890 B；预留 `RELEASED`，`used_bytes=34,188,890` |
| B 释放后重试 | **200**，记录 id=8 RUNNING → SUCCESS |
| 预留表终态 | 6 条全部 `RELEASED`，无负数、无重复释放 |

> 关键证据：B 的消息里出现「已预留 100 MB」——这 100 MB 是**另一个控制面进程**在数据库里写入的预留。
> 说明悲观锁锁定的是同一行（同一 `storage_key`），而不是各自创建了独立容量记录。

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
| 双实例容量竞争 E2E | ✅ 已验证（507 + 已预留可见 + 释放后重试成功） |
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