# 控制台 REST API

> **契约以代码为准**：`docs/api/openapi.json` 由 `/v3/api-docs` 生成，
> 由 `OpenApiSnapshotTest` 在 CI 中比对防漂移；前端类型
> `console-web/src/api/schema.d.ts` 由 `npm run gen:api` 生成。
> 接口改动后执行：
> ```bash
> cd console-server && mvn test -Dopenapi.update=true   # 重新生成快照
> cd ../console-web && npm run gen:api                  # 同步前端类型
> ```
> 在线文档：`/swagger-ui/index.html`（只读，无需登录）。

Base：`/api/v1` · 认证：`Authorization: Bearer <JWT>` · 响应：

```json
{ "code": 0, "message": "ok", "data": {} }
```

错误码：`40000` 参数错误 / `40100` 未认证 / `40300` 权限不足 /
`40400` 不存在 / `40900` 冲突 / `50200` 上游（内核）异常 / `50000` 服务内部错误。

## 1. 认证

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/auth/login` | 登录，返回 JWT 与用户信息 | 公开 |
| GET | `/auth/me` | 当前用户 | 登录 |

```json
POST /auth/login
{ "username": "admin", "password": "admin123" }

200 { "code": 0, "data": { "token": "eyJ...", "expiresInSeconds": 43200,
      "user": { "id": 1, "username": "admin", "role": "ADMIN", "status": 1 } } }
```

## 2. 用户管理（ADMIN）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/users?keyword=&page=1&size=20` | 分页查询 |
| POST | `/users` | 新建（`username/password/role` 必填） |
| PUT | `/users/{id}` | 修改昵称/邮箱/角色/状态 |
| PUT | `/users/{id}/password` | 重置口令 |
| DELETE | `/users/{id}` | 删除（禁止删除自己与最后一个管理员） |

## 3. 实例

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/instances` | 列表（不含口令） | 登录 |
| POST | `/instances` | 纳管 | OPS/ADMIN |
| PUT | `/instances/{id}` | 修改（口令留空表示不改） | OPS/ADMIN |
| DELETE | `/instances/{id}` | 取消纳管 | ADMIN |
| POST | `/instances/{id}/probe` | 连通性探测，回写状态与版本 | 登录 |

## 4. 仪表盘

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/dashboard/overview?instanceId=1` | 现场 INFO 实时指标 + 告警计数 |
| GET | `/dashboard/trends?instanceId=1&minutes=30` | 历史采样序列（默认近 30 分钟） |

```json
GET /dashboard/trends?instanceId=1&minutes=30
200 { "code": 0, "data": [
  { "time": "2026-10-23T23:11:16.076", "qps": 0.0, "avgLatencyMs": 0.0,
    "connections": 0, "usedMemory": 3146679, "totalKeys": 11,
    "errors": 0, "maxMemory": 1073741824 } ] }
```

## 5. 键空间

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/keys/scan?instanceId=&cursor=0&pattern=*&count=50` | SCAN 分页（count ≤ 200） |
| GET | `/keys/detail?instanceId=&key=` | 按类型展开值 |
| PUT | `/keys?instanceId=` | 写入字符串键 `{key,value}`（仅 string/不存在，否则 400） |
| PUT | `/keys/hash-field?instanceId=` | 写 hash 字段 `{key,field,value}`（仅 hash/不存在） |
| DELETE | `/keys/hash-field?instanceId=&key=&field=` | 删除 hash 字段（字段不存在 404） |
| PUT | `/keys/ttl?instanceId=` | 设置过期 `{key,ttlSeconds}`（≤0 表示持久化） |
| DELETE | `/keys?instanceId=&key=` | 删除 |

> 类型守卫（TD-089）：list/set/zset/json 目前只读；`SET` 命中非 string 键、
> `HSET` 命中的键已存在且非 hash 时返回 `40000`，避免破坏原有结构。

## 6. 终端

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/terminal/execute` | `{instanceId, command}`，返回 `{replyType,payload,costMs,error}` |
| GET | `/terminal/history?page=1&size=20` | 命令历史 |

## 7. 告警

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/alerts/rules` | 规则列表 / 新建 |
| PUT/DELETE | `/alerts/rules/{id}` | 修改 / 删除 |
| GET | `/alerts/events?status=FIRING&page=1&size=20` | 事件分页 |
| POST | `/alerts/events/{id}/resolve` | 手动标记恢复 |

可用指标：`qps`、`ops_per_sec`、`avg_latency_ms`、`connections`、
`active_requests`、`errors`、`used_memory`、`max_memory`、
`used_memory_ratio`、`total_keys`、`uptime_seconds`。

## 8. 备份

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/backups?instanceId=` | 备份记录 | 登录 |
| POST | `/backups?instanceId=` | 提交备份任务（**异步**，返回 RUNNING 记录；同实例并发提交返回 `40900`） | OPS/ADMIN |
| GET | `/backups/{id}/download` | 下载 JSONL | 登录 |
| POST | `/backups/{id}/restore` | 回放恢复 | ADMIN |
| DELETE | `/backups/{id}` | 删除记录与文件 | OPS/ADMIN |

> 异步语义（TD-091）：提交后用 `GET /backups` 轮询状态（RUNNING → SUCCESS/FAILED）；
> 前端每 2 秒刷新一次，最多观察 60 秒。超过 30 分钟仍为 RUNNING 的僵尸任务会在下次提交时被标记失败。

## 9. 审计（ADMIN）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/audit?action=&username=&success=&from=&to=&page=1&size=20` | 操作日志分页（动作/用户模糊匹配，结果布尔，时间 ISO-8601） |
| GET | `/audit/export?<同上筛选>` | 导出 CSV（UTF-8 BOM，最多 10000 条，带 `Content-Disposition`） |

## 10. 系统信息（只读）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/system/info?instanceId=` | `INFO` 全段落 + `CONFIG GET *` 的只读快照：版本/运行时长/端口/连接数/内存/命中率/键空间/白名单配置 |

> 取不到的段落（例如内核禁用 CONFIG）不会让整个接口失败，而是放进 `warnings` 数组，
> 页面上以黄色提示条展示（TD-092）。
