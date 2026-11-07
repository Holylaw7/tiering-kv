# ADR-0368：生产配置安全启动门禁

- 状态：Accepted
- 日期：2026-11-07
- 关联：G0-3、ADR-0366/0367

## 背景

`application.yml` 中 JWT 密钥与数据源口令都带开发默认值：

```yaml
jwt:
  secret: ${CONSOLE_JWT_SECRET:tiering-kv-console-dev-secret-key-change-me-please-0123456789}
```

同时 `BootstrapDataInitializer` 在库里没有用户时会创建 `admin/admin123`。
这些默认值对开发是便利，对生产是**可直接利用的漏洞**：忘记配置环境变量的部署会"正常启动"，
但令牌可离线伪造、管理员口令是公开的。

## 决策

### 1. 只在 prod profile 生效

新增 `@Profile({"prod","production"})` 的配置守卫，在 bean 初始化阶段（`afterPropertiesSet`）校验，
校验失败直接抛异常终止上下文。由于 Tomcat 在上下文刷新末尾才启动，**失败发生在对外提供服务之前**。
开发与测试 profile 完全不受影响。

### 2. 检查项分三级

| 级别 | 含义 | 处置 |
| --- | --- | --- |
| FATAL | 生产不可接受的配置 | 启动失败 |
| WARN | 可启动但有风险 | 记录日志 |
| INFO | 提示 | 记录日志 |

### 3. JWT 密钥

FATAL：缺失、空白、等于已知默认值、长度 < 32 字节、字符多样性 < 10、包含 `change-me`/`please-change`。
长度检查按 **UTF-8 字节**而非字符数（HS256 需要 256 位密钥）；字符串长度不足以覆盖算法约束这一点已在测试中固定。
日志只输出"配置项名称 + 原因"，**不打印密钥内容或其片段**。

### 4. 数据库凭据

FATAL：口令为空、等于示例值（`root`、`password`、`changeme`、`admin123`）。
WARN：用户名为 `root`、URL 指向 `127.0.0.1`/`localhost`。
数据库最小权限与网络隔离不在本 ADR 范围，作为后续强化项记录。

### 5. 默认管理员

- dev/test：保留 `admin/admin123` 初始化（便利优先，日志告警）；
- prod：必须提供 `CONSOLE_ADMIN_PASSWORD`（≥ 12 位且不等于 `admin123`），否则 FATAL；
- 守卫**不修改任何已有用户密码**，只做校验；管理员口令仍由 `BootstrapDataInitializer` 在首次初始化时写入。

## 后果

- 生产部署忘记配密钥/口令会**启动失败**而不是带病运行；
- 需要运维在部署清单中显式提供两个环境变量，Docker/K8s Secret 可直接覆盖；
- 检查逻辑集中在 `ProductionConfigGuard`，新增检查只需追加一条规则；
- 不是"安全加固"的全部：数据库最小权限、TLS、审计留存仍待后续。

## 验证

| 场景 | 期望 |
| --- | --- |
| prod 缺 JWT | 启动失败，非零退出 |
| prod 默认 JWT | 启动失败 |
| prod 弱 JWT | 启动失败 |
| prod 合法随机密钥 + 合法口令 + 真实 MySQL | 启动成功，健康检查 200 |
| dev 默认配置 | 正常启动 |
| 日志 | 不含任何密钥/口令片段 |
## 验证结果（2026-11-07）

| 场景 | 实测 |
| --- | --- |
| prod 缺 JWT | EXIT=1 |
| prod 默认 JWT | EXIT=1，消息只含配置项名 |
| prod 合法配置 + 真实 MySQL | 启动成功，/actuator/health 200 |
| dev 默认配置 | 启动成功，health 200 |
| 日志泄露检查 | 密钥/管理员口令/数据库口令均未出现在日志中 |

证据：`docs/planning/g0-3-acceptance-evidence.md`；单测 8 例；控制面全量 117 例通过。
