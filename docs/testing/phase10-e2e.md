# Phase 10：端到端业务闭环验收

- 日期：2026-11-06
- 基线：`38b4097`（Phase 9 完成）
- 范围：7 条 API 业务闭环 + 4 类故障注入 + 3 组 Playwright 场景 + 覆盖率与前端单测
- 原则：**只认实测**；截图只是证据，PASS 由断言决定

## 0. 结论速览

| 验收项 | 结果 |
| --- | --- |
| 7 条业务闭环 | **全部通过**（82 条断言） |
| 4 类故障注入 | **全部通过且环境恢复**（F1 6 / F2 5 / F3 8 / F4 3） |
| Playwright 三组场景 | **17/17 通过** |
| 控制面覆盖率 | LINE **62.76%**（1321/2105）、BRANCH 40.80%、INSTR 59.25%（103 个类） |
| 前端单测 | Vitest **20/20** |
| 内核回归 | **15,012 通过 / 0 失败 / 13 跳过** |
| 控制面回归 | **90 通过 / 0 失败**（20 个测试类） |
| UI 回归 | **12/12** |
| 工程检查 | build ✅、typecheck ✅ |

执行过程中发现并修复了 **3 个真实缺陷**（备份并发、审计回滚、WAL 空闲不落盘），
每一个都有"修复前复现 → 修复后复测"的证据，见 §5。

## 1. 环境与隔离

| 组件 | 端口 | 说明 |
| --- | --- | --- |
| 专用测试 MySQL | 3307 | `dev-mysql.ps1` 启动，datadir=`%LOCALAPPDATA%\tieringkv-mysql\data`，**本工程专用**，非共享实例 |
| 主内核 | 6379 | 对照实例 |
| 测试内核 A | 6383 | 业务闭环主用（16MB 上限，独立 temp 目录） |
| 测试内核 B | 6384 | 实例管理用例专用（避免 host:port 唯一约束冲突） |
| 控制面 | 8080 | `admin/admin123` |
| 前端 | 5173 | Vite dev |

> F3 停库前已核实 `dev-mysql.ps1` 启动的是本工程独立实例（专属 datadir、专属端口），
> 不是其他项目共享的开发库。

## 2. 七条业务闭环（API 级）

| 用例 | 断言数 | 关键证据 |
| --- | --- | --- |
| E2E-1 认证与权限 | 10 | 改密后旧口令 401 / 新口令 200；VIEWER 写键空间 403、读审计 403、读备份 200；匿名 401 |
| E2E-2 实例管理 | 9 | 纳管→探测 UP(5.1.0)→不可达实例 DOWN→离线 INFO 502→改备注→删除后列表不含 |
| E2E-3 键空间 | 14 | 值/类型一致；hash 两字段写入、删一留一；TTL 到期后**内核 EXISTS=0** 且控制面 404；删除后 EXISTS=0 且未误删其他键 |
| E2E-4 在线终端 | 11 | PING→PONG；SET/GET 回读；未知命令 error=true；FLUSHALL 被策略拦截 400；历史按实例隔离 |
| E2E-5 备份 | 13 | 3006 键 SUCCESS、文件 386,403 B、下载带 attachment；破坏数据后恢复，**含逗号/引号的值原样还原**；并发 200+409 |
| E2E-6 告警 | 10 | 唯一规则名 + `total_keys < 1e12` 确定性触发 → FIRING → resolve → RESOLVED（含 resolvedAt） |
| E2E-7 审计 | 15 | 分页/字段/action 过滤；CSV 带 UTF-8 BOM、中文表头、每行列数与表头一致（真解析器，非 split） |

合计 **82 条断言全部通过**。

## 3. 四类故障注入

### F1 实例离线（6 条）
- `overview` 200 但 `live` 为空、`selected.status=DOWN`；
- `/system/tables` → **502/50201**，消息为 `无法连接实例 127.0.0.1:6399（Connection refused: getsockopt）`，不含 SQL/堆栈。

### F2 内核崩溃恢复（5 条）
- 写入 100 个唯一键 → 等待超过 fsync 周期 → `Stop-Process -Force` 强杀 → 重启；
- **100/100 键存在、值无损坏**，`DBSIZE ≥ 100`；
- 进程级计数器归零（flush=0, compaction=0），`tieringReported=true` 仍然成立。

### F3 数据库断连（8 条，专用实例）
| 阶段 | 实测 |
| --- | --- |
| 基线 | 登录 200 |
| 停止 MySQL 后请求 | **5,050 ms 返回 500**（Hikari `connection-timeout=5000`），**未挂起** |
| 错误内容 | `服务内部错误：Could not open JPA EntityManager for transaction`，无 SQL/凭据/堆栈 |
| 恢复 MySQL 后**不重启控制面** | 登录 **200**，耗时 2,388 ms → 连接池自愈 |
| 健康检查 | `/actuator/health` 200 |

### F4 Token（3 条）
- 篡改签名 → 401；
- **签名有效但 exp 已过期**（用开发密钥自签）→ 401；
- 对照：签名有效且未过期的自签 Token → 200，证明 401 来自过期而非签名。

## 4. Playwright 三组场景（17 条断言）

| 场景 | 断言要点 |
| --- | --- |
| UI-1 在线终端 | 连续 4 条命令按序渲染、每条只渲染一次、错误命令带 `.reply.error`、标题含实例名 |
| UI-2 状态与业务页面 | 离线实例：顶部"连接失败"、6 个指标全部显示 **—**（不是 0）、分层区红色提示；告警页显示"触发中"→恢复后显示"已恢复"；备份页显示 SUCCESS |
| UI-3 权限与认证 | VIEWER 访问审计出现"权限不足"提示；写入过期 Token 后刷新 → 跳转 `#/login` 且表单可用 |

截图：`docs/screenshots/v2/ui-01-terminal.png` … `ui-06-login-redirect.png`。

## 5. 发现的真实缺陷与修复

| # | 缺陷 | 证据 | 修复 |
| --- | --- | --- | --- |
| TD-101 | 同一实例**同时**提交备份会双双成功 | 并发探针 **5/5 轮均 200/200** | `export()` 按实例加锁，把"检查 RUNNING + 落库"变为原子；修复后 5/5 轮 **200/409**，并新增并发单测 |
| TD-102 | 失败操作的审计被业务事务回滚 | 终端 FLUSHALL 返回 400，但 `action=TERMINAL_BLOCKED` 查不到记录 | `AuditService.record` 改为 `REQUIRES_NEW`；修复后 `success=false` 可查到该记录，新增 MockMvc 用例 |
| TD-103 | `wal.fsync=EVERY_SEC` 空闲期不落盘，崩溃丢数据 | 写 100 键、等 1.8s 后强杀 → **丢失 99 个** | WAL 增加后台 500ms 强制 flush+fsync；修复后同场景 **100/100 存活**，新增 `WALDurabilityTest` |

三个修复都有"修复前失败 → 修复后通过"的对照，不是预防性改动。

## 6. 覆盖率（JaCoCo，实测）

`mvn -s maven-settings.xml test`（`report` 绑定到 test 阶段，数据来源 `target/site/jacoco/jacoco.csv`）：

| 指标 | Covered | Missed | 覆盖率 |
| --- | --- | --- | --- |
| LINE | 1,321 | 784 | **62.76%** |
| BRANCH | 264 | 383 | 40.80% |
| INSTRUCTION | 5,861 | 4,031 | 59.25% |

- 统计范围：103 个类（`console-server` 全部 main 源）；
- **未排除任何业务类**，DTO/record 的 getter 也计入，因此数字偏低但真实；
- 不设及格线，作为后续可比基线。

## 7. 前端单测（Vitest）

新增 `vitest` + `vitest.config.ts`（仅 devDependency，运行时零依赖）：

| 文件 | 用例 | 内容 |
| --- | --- | --- |
| `src/utils/format.test.ts` | 8 | 字节/数字/百分比格式化，0、空值、边界 |
| `src/utils/inventory.test.ts` | 12 | 排序（id/keys/bytes、不修改入参、稳定性）、分页（首末页/越界/非法页大小）、页数 |

抽屉的排序与分页逻辑抽出为 `src/utils/inventory.ts`，组件只做渲染，
这样这段"后端数据 → 界面顺序"的逻辑可被单测覆盖。

## 8. 修改文件清单

**内核**

- `storage/wal/WALManager.java`（后台 fsync）
- `test/.../storage/wal/WALDurabilityTest.java`（新增）

**控制面**

- `service/BackupService.java`（按实例串行化）
- `service/AuditService.java`（REQUIRES_NEW）
- `pom.xml`（JaCoCo + surefire `@{argLine}`）
- `test/.../service/BackupServiceAsyncTest.java`（并发用例）
- `test/.../web/AuditFailureTrailTest.java`（新增）

**前端**

- `src/utils/inventory.ts`（新增）、`src/views/DashboardView.vue`（改用工具函数）
- `src/utils/format.test.ts`、`src/utils/inventory.test.ts`（新增）
- `vitest.config.ts`（新增）、`package.json`（`test` 脚本 + devDependency）

**测试资产（新增）**

```
console-web/scripts/phase10/lib.mjs               公共库（API/断言/等待/RESP/CSV）
console-web/scripts/phase10/e2e-01-auth.mjs       认证与权限
console-web/scripts/phase10/e2e-02-instance.mjs   实例管理
console-web/scripts/phase10/e2e-03-keyspace.mjs   键空间
console-web/scripts/phase10/e2e-04-terminal.mjs   在线终端
console-web/scripts/phase10/e2e-05-backup.mjs     备份恢复
console-web/scripts/phase10/e2e-06-alert.mjs      告警
console-web/scripts/phase10/e2e-07-audit.mjs      审计
console-web/scripts/phase10/faults-api.mjs        F1 / F2 / F4
console-web/scripts/phase10/f3-stage.mjs          F3 分阶段断言
console-web/scripts/phase10/f3-db-outage.ps1      F3 编排（停库→断言→恢复→自愈）
console-web/scripts/phase10/ui-flows.mjs          Playwright 三组场景
console-web/scripts/phase10/run-e2e.ps1           全量复跑入口
console-web/scripts/phase10/probe-backup-race.mjs 并发复现探针（缺陷证据）
```

**报告**

`docs/testing/phase10-e2e.md`（本文）、`docs/screenshots/v2/ui-0*.png`

## 9. API 契约变化

**无**。本阶段未新增/修改任何接口，未改变既有状态码语义
（F3 的 500 属于既有全局异常处理行为，见 §10）。

## 10. 已知问题与未覆盖项

1. **DB 断连错误语义**：当前返回 `500` + "Could not open JPA EntityManager for transaction"。
   语义上 `503` 更准确、文案可更友好，但改动会影响全局异常处理，**记录为待办未修改**。
2. **备份恢复是全量回放**：实测恢复 3006/3008 键，没有增量/冲突策略（备份为逻辑快照）。
3. **审计只覆盖部分动作**：目前记录 LOGIN / 键操作 / 实例 / 告警 / 用户 / 终端；
   备份创建未写审计（`CREATE_BACKUP` 查询为空），属于功能缺口，未在本阶段扩大范围。
4. **F2 的持久化窗口**：修复后为"后台每 500ms 落盘"，极端情况下仍可能丢失最近 ≤500ms 的写入，
   与 `EVERY_SEC` 语义一致；未做崩溃瞬间的字节级一致性校验（依赖 WAL CRC，已有既有用例覆盖）。
5. **未覆盖**：并发 Compaction 期间的业务闭环（Phase 9 已单独覆盖快照一致性）、
   多浏览器兼容、移动端布局。

## 11. 复现命令

```powershell
# 一次性全量复跑（10 个用例，含崩溃恢复）
cd console-web; pwsh -NoProfile -File scripts/phase10/run-e2e.ps1

# 故障注入（单独）
node scripts/phase10/faults-api.mjs f1|f4
pwsh -NoProfile -File scripts/phase10/f3-db-outage.ps1

# UI 三组场景
node scripts/phase10/ui-flows.mjs

# 覆盖率 + 单测
cd ../console-server; mvn -s maven-settings.xml test   # 报告在 target/site/jacoco
cd ../console-web; npm test
```

## 12. Git 记录

| 提交 | 内容 |
| --- | --- |
| `9acf3c8` | `test(e2e): Phase 10 端到端业务闭环与故障注入（TD-101/102/103）` —— 7 条闭环、4 类故障注入、3 组 UI 场景、3 处缺陷修复、测试数据清理 |
| 本提交 | `test(coverage): 控制面 JaCoCo + 前端 Vitest + Phase 10 报告` —— 覆盖率、单测、本报告 |

all 测试数据已清理：`phase10-*` 实例/用户/规则与备份记录均已删除，`console-server/backups/` 已加入 `.gitignore`。