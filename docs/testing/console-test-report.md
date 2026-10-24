# Web 运维管理平台测试报告

- 版本：console v1.0（分支 `feature/console-platform`）
- 日期：2026-10-24
- 环境：Windows 11 · JDK 21.0.12（Microsoft） · Maven 3.9.16 · Node 24.16 · MySQL 8.0.39 · Docker 29.8

## 1. 测试策略

| 层级 | 覆盖对象 | 工具 | 数量 |
| --- | --- | --- | --- |
| 单元测试 | RESP 编解码、INFO 解析、终端分词、指标映射、限流器 | JUnit 5 + AssertJ | 26 |
| 接口测试 | 认证、鉴权、用户管理、告警求值 | Spring Boot Test + MockMvc（H2） | 19 |
| 接口端到端 | 25 项业务断言，跑在真实内核 + MySQL 之上 | Node 脚本 | 25 |
| 界面冒烟 | 登录 → 总览 → 键空间 → 终端 → 告警 → 实例 | Playwright (Chromium) | 6 页 |
| 内核回归 | 全量单元/集成测试 | JUnit 5 + Surefire | 14,958 次执行 |
| 性能 | 接口延迟基线、内核吞吐 | 自研脚本 | 3 组 |

## 2. 执行结果

### 2.1 控制面测试（`cd console-server && mvn test`）

```
Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 用例类 | 数量 | 关注点 |
| --- | --- | --- |
| `RespCodecTest` | 10 | 命令编码、5 类应答、嵌套数组、nil、截断/非法前缀 |
| `InfoParserTest` | 5 | section 解析、类型化取值、Keyspace 求和、空输入 |
| `MetricMapperTest` | 3 | INFO → 指标模型、告警字典、内存水位 |
| `TerminalTokenizerTest` | 5 | 引号/转义/空白折叠 |
| `LoginAttemptGuardTest` | 4 | 5 次失败锁定、成功清零、大小写与空格、未知用户 |
| `AuthApiTest` | 6 | 登录成功/失败、空参数、匿名 401、/me、VIEWER 越权 |
| `UserAdminTest` | 4 | 创建、重名 409、口令长度、不能删除自己、口令不外泄 |
| `LoginRateLimitTest` | 1 | 连续失败后 429 |
| `AlertServiceTest` | 4 | 触发/恢复闭环、不重复触发、实例绑定、停用规则 |

### 2.2 内核回归（`mvn -o -B '-Dsurefire.excludedGroups=benchmark' test`）

```
[WARNING] Tests run: 14958, Failures: 0, Errors: 0, Skipped: 13
[INFO] BUILD SUCCESS
Total time: 07:35 min
```

覆盖本轮对内核的两处改动：`INFO` 标准 section（ADR-0355）与
`MetricsRegistry` 接线修复（TD-082），新增 8 个用例在其中。

### 2.3 接口端到端（`node scripts/api-smoke.mjs`）

25/25 通过，覆盖：登录/错误口令/匿名拦截、实例重复纳管 409、探测、
终端读写、危险命令拦截、键详情与 TTL、告警触发与人工恢复、
备份→删除→恢复闭环、审计留痕、只读角色越权。
同一脚本对**容器化部署**（`--base http://127.0.0.1:8081`）重跑一遍，同样 25/25。

### 2.4 界面冒烟（`node scripts/smoke.mjs`）

6 个页面渲染通过，无 console error / pageerror，产出
`console-web/screenshots/`（本地）与 `screenshots/docker/`（容器化）。

### 2.5 性能观测

接口延迟（200–300 次请求，并发 8）：

| 接口 | 平均 | P50 | P95 | P99 | 吞吐 |
| --- | --- | --- | --- | --- | --- |
| `/dashboard/overview`（含一次实时 INFO） | 19.87 ms | 14.65 ms | 25.49 ms | 136.18 ms | 400 req/s |
| `/dashboard/trends`（纯 DB 查询） | 8.53 ms | 7.72 ms | 10.13 ms | 35.99 ms | 924 req/s |
| `/instances`（纯 DB 查询） | 6.55 ms | 6.25 ms | 8.85 ms | 15.68 ms | 1202 req/s |

目标为 P95 < 200 ms，实测最好项 8.85 ms、最差项 25.49 ms，均达标。

内核吞吐（`node scripts/demo-load.mjs --seconds 70 --conns 6`）：
**67,399 ops/s**（4,718,099 次请求，6 条连接），同期 `INFO` 读数
`qps=34,288.5 / avg_latency_ms=0.072`。

## 3. 缺陷记录

| # | 现象 | 根因 | 处置 | 验证 |
| --- | --- | --- | --- | --- |
| D1 | 控制面启动失败：`wrong column type [enabled]; found tinyint, expecting bit` | Hibernate 6 把 boolean 映射为 BIT，与 DDL 的 TINYINT 不符 | 库表由 Flyway 独占（`ddl-auto: none`） | 启动成功，V1/V2 正常迁移 |
| D2 | 告警测试报 `Syntax error ... [*]value` | `value` 是 H2 保留字 | 列名改为 `metric_value` | H2 + MySQL 双库通过 |
| D3 | `Surefire` 报 "Error occurred in starting fork" | pom 里写死 `@{argLine}`，本模块未接 JaCoCo，字面量传给 JVM | 去掉 `@{argLine}` | 45 用例正常执行 |
| D4 | 仪表盘 QPS/连接数恒为 0 | `Main` 新建了第二个 `MetricsRegistry`，INFO 读的与实际埋点不是同一实例 | 复用同一实例（TD-082） | 新增 `ServerMetricsIntegrationTest` |
| D5 | 预览构建登录 403 | CORS 白名单只含 5173 | 白名单补 4173；容器内由 Nginx 剥离 Origin | 两种部署均登录成功 |
| D6 | Vite dev server 崩溃（EBUSY） | 工具以 `.xxx.tmpdir` 原子写文件，chokidar 监听到瞬态目录 | `server.watch.ignored` 忽略临时目录/脚本/截图 | 连续编辑不再崩 |
| D7 | 镜像构建失败：`/build/target/*.jar: No such file` | 选包命令写在了运行阶段，构建产物不在该阶段 | 移入构建阶段并固定路径 | 镜像构建成功 |
| D8 | 镜像内 `mvn package` 失败 403 | 本机网络访问 Maven Central 被 CDN 限流 | 构建期用阿里云镜像 + BuildKit 依赖缓存 | 3 个镜像全部构建成功 |
| D9 | 容器内实例状态 DOWN | 种子实例是 `127.0.0.1:6379`，在控制面容器里指向自身 | `CONSOLE_BOOTSTRAP_INSTANCE` 启动引导改写地址 | `status=UP / version=4.1.0` |
| D10 | 告警/实例表格列错位、时间被截断 | `fixed="right"` 在隐藏 tab 中测量宽度 | 去掉 fixed、加宽时间列 | 截图复核通过 |

## 4. 未覆盖与已知限制

| 项 | 说明 | 影响 |
| --- | --- | --- |
| Windows 下的真实网络混沌 | `RealNetworkChaosTest` 仅在 Linux + 环境变量开启时执行（本轮 13 skipped 之一） | 故障演练需在 Linux/CI 跑 |
| 多副本控制面 | 登录限流、采集调度为单实例内存实现 | 横向扩展需引入共享存储/分布式锁 |
| 备份规模 | 逻辑备份为 O(keys) 次往返，上限 20 万键 | 千万级键空间应改用物理备份 |
| 前端单元测试 | 只有 Playwright 冒烟，没有组件级单测 | 回归依赖界面冒烟 |

## 5. 复现方式

```powershell
. .\scripts\env.ps1
cd console-server; mvn -o test          # 45 用例
cd ..\tiering-kv; mvn -o -B '-Dsurefire.excludedGroups=benchmark' test   # 内核全量
node scripts\api-smoke.mjs              # 接口 25 项
node scripts\api-bench.mjs --n 200      # 接口延迟
cd console-web; node scripts\smoke.mjs  # 界面 6 页 + 截图
.\scripts\quality-gates.ps1             # 一键门禁
```
