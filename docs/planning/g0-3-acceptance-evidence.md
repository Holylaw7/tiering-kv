# G0-3 验收证据：生产配置安全启动门禁

- 设计：`docs/adr/ADR-0368-production-config-security-gate.md`
- 环境：Windows 11 / JDK 21.0.12 / MySQL 8.0.39 / Tiering-KV 5.1.0

## 1. 启动场景

| 场景 | 配置 | 期望 | 实测 |
| --- | --- | --- | --- |
| A 生产缺 JWT | prod，无环境变量 | 非零退出 | **EXIT=1**，失败于 `console.jwt.secret` |
| B 生产用默认 JWT | prod + 开发默认密钥 + 合法 DB 与管理员口令 | 非零退出 | **EXIT=1**，`生产配置校验失败，拒绝启动：console.jwt.secret` |
| C 生产合法配置 | prod + 48 位随机密钥 + 12 位以上管理员口令 + 非 root 数据库账户 + 真实 MySQL | 启动成功 | **启动成功**，`/actuator/health` → **200**，日志"生产配置校验通过（5 项）" |
| D 开发默认配置 | 无 prod profile | 正常启动 | 启动成功，`/actuator/health` → **200** |

## 2. 拒绝发生在对外服务之前

Scenario B 的日志顺序证明这一点：

```text
ERROR ... ProductionConfigGuard : 生产配置不通过：console.jwt.secret - 仍为开发默认值
WARN  ... ConfigServletWebServerApplicationContext : Exception encountered during context initialization - cancelling refresh
Caused by: java.lang.IllegalStateException: 生产配置校验失败，拒绝启动：console.jwt.secret
```

守卫在 bean 初始化阶段抛异常，Web 服务器尚未完成启动，端口未进入监听状态。

## 3. 日志不泄露凭据

对成功启动的日志（Scenario C）做字符串检索：

```text
contains_secret      = False   (JWT 密钥)
contains_admin_pass  = False   (管理员口令)
contains_config_pass = False   (数据库口令)
```

守卫只输出配置项名称、级别与原因，例如：

```text
生产配置提醒：spring.datasource.url - 指向本机地址，确认是否符合部署拓扑
生产配置：CONSOLE_ADMIN_PASSWORD - 已配置
```

## 4. 检查项清单

| 级别 | 配置项 | 条件 |
| --- | --- | --- |
| FATAL | console.jwt.secret | 缺失 / 空白 / 等于开发默认值 / < 32 字节 / 字符多样性 < 10 / 含示例占位文本 |
| FATAL | spring.datasource.password | 空 / 示例弱口令（root、password、changeme、admin123…） |
| FATAL | CONSOLE_ADMIN_PASSWORD | 未配置 / 等于 admin123 / 长度 < 12 |
| WARN | spring.datasource.username | 使用 root |
| WARN | spring.datasource.url | 指向 127.0.0.1 / localhost |
| INFO | — | 各强制项通过 |

## 5. 自动化测试

| 测试 | 覆盖 |
| --- | --- |
| `ProductionConfigGuardTest`（8 例） | 缺失/默认/弱密钥（短、低多样性、含 change-me）、合法密钥、空口令、示例口令、默认管理员口令、异常消息不含密钥 |
| 控制面全量 | **117 例，0 失败**（原 109 + 8） |

## 6. 已发现并修正的问题

| 问题 | 说明 |
| --- | --- |
| `console.bootstrap` 配置块缺失 | 守卫读取管理员口令为 null；补入 yml（`admin-password` / `admin-username`） |
| 已有 JwtService 仅检查长度 | 默认密钥长度 60 可通过旧检查；新守卫补充"默认值/弱密钥"判定 |

## 7. 未覆盖（不得默认已解决）

| 项 | 状态 |
| --- | --- |
| 数据库最小权限与网络隔离 | 未做，仅 WARN 提示 root |
| 密钥轮换 | 未实现 |
| K8s/Docker Secret 注入链路 | 未在本环境验证（无 K8s） |
| 已有管理员口令强度 | 仅校验初始化口令，不校验历史账户 |
| 配置自检的单元测试 vs 真实 Spring 启动 | 两者都做了（8 例单测 + 4 个真实启动场景） |

## 8. 复现方式

```powershell
# A：缺少密钥
java -jar target/tiering-kv-console-5.1.0.jar --spring.profiles.active=prod --server.port=18080

# B：默认密钥
$env:CONSOLE_JWT_SECRET='tiering-kv-console-dev-secret-key-change-me-please-0123456789'
java -jar ... --spring.profiles.active=prod ...

# C：合法配置
$env:CONSOLE_JWT_SECRET='<48 位随机>'; $env:CONSOLE_ADMIN_PASSWORD='<12 位以上>'
java -jar ... --spring.profiles.active=prod --spring.datasource.username=console_app --spring.datasource.password=<口令>
curl http://127.0.0.1:18080/actuator/health
```
