# Tiering-KV Console Server（控制面后端）

面向 Tiering-KV 内核的 Web 运维管理平台后端，Spring Boot 3.3 / JDK 21 / MySQL 8。

## 职责边界

| 层 | 组件 | 说明 |
| --- | --- | --- |
| 数据面 | `tiering-kv` 内核 | RESP 协议，157 条命令，负责存储与执行 |
| 控制面 | 本模块 | 认证授权、实例纳管、指标采集、键空间浏览、在线终端、告警、备份、审计 |
| 展示面 | `console-web` | Vue 3 控制台 |

控制面**不直接依赖内核 jar**，通过 TCP + RESP 协议访问，等价于任意 Redis 客户端，
避免与内核形成编译期耦合。

## 本地启动

```powershell
# 1) 环境（JDK 21）
. ..\scripts\env.ps1

# 2) 数据库（项目内自带脚本，无需管理员权限，端口 3307）
..\scripts\dev-mysql.ps1

# 3) 打包 + 启动
mvn -DskipTests package
java -jar target\tiering-kv-console-1.0.0.jar
```

默认账号：`admin / admin123`（首次启动自动创建，请立即修改）。

## 配置

见 `src/main/resources/application.yml`，全部支持环境变量覆盖：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CONSOLE_PORT` | 8080 | HTTP 端口 |
| `CONSOLE_DB_URL` | jdbc:mysql://127.0.0.1:3307/tiering_console | 数据库 |
| `CONSOLE_DB_USER` / `CONSOLE_DB_PASSWORD` | root / 空 | 数据库账号 |
| `CONSOLE_JWT_SECRET` | 开发密钥 | 生产必须覆盖（≥32 字节） |
| `CONSOLE_METRICS_INTERVAL` | 5000 | 指标采集周期（ms） |
| `CONSOLE_METRICS_RETENTION` | 72 | 指标保留（小时） |

## 接口一览（/api/v1）

| 模块 | 端点 |
| --- | --- |
| 认证 | `POST /auth/login`、`GET /auth/me` |
| 用户 | `GET/POST /users`、`PUT/DELETE /users/{id}`、`PUT /users/{id}/password` |
| 实例 | `GET/POST /instances`、`PUT/DELETE /instances/{id}`、`POST /instances/{id}/probe` |
| 仪表盘 | `GET /dashboard/overview`、`GET /dashboard/trends` |
| 键空间 | `GET /keys/scan`、`GET /keys/detail`、`PUT /keys`、`PUT /keys/ttl`、`DELETE /keys` |
| 终端 | `POST /terminal/execute`、`GET /terminal/history` |
| 告警 | `GET/POST /alerts/rules`、`PUT/DELETE /alerts/rules/{id}`、`GET /alerts/events`、`POST /alerts/events/{id}/resolve` |
| 备份 | `GET/POST /backups`、`POST /backups/{id}/restore`、`GET /backups/{id}/download`、`DELETE /backups/{id}` |
| 审计 | `GET /audit` |

## 数据库

由 Flyway 管理（`src/main/resources/db/migration`），8 张表：
`sys_user`、`op_log`、`db_instance`、`metric_point`、`alert_rule`、
`alert_event`、`backup_record`、`terminal_history`。
