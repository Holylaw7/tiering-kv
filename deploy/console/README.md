# 全栈容器部署（控制台 + 内核 + MySQL）

## 一键启动

```bash
cd deploy/console
docker compose up -d --build
```

| 服务 | 容器 | 宿主机端口 | 说明 |
| --- | --- | --- | --- |
| 数据面 | `tk-core` | 6379 | `io.tieringkv.Main`，挂载 `../../config` |
| 控制面 | `tk-console` | 内部 8080 | Spring Boot，Flyway 自动建表 |
| 前端 | `tk-web` | 8081 | Nginx 静态站点 + `/api` 同源反代 |
| 数据库 | `tk-mysql` | 内部 3306 | MySQL 8，`tiering_console` 库 |

访问 **http://localhost:8081**，默认账号 `admin / admin123`。

停止：`docker compose down`；清库重来：`docker compose down -v`。

## 已验证结果（2026-10-24）

| 项 | 结果 |
| --- | --- |
| 镜像构建 | 3 个镜像全部成功（`tk-core` 509MB / `tk-console` / `tk-web` 76.5MB） |
| 容器状态 | 4 个服务 `Up`，MySQL `healthy` |
| 数据面连通 | 宿主机 `PING` → `+PONG` |
| 控制面→数据面 | 实例地址自动改写为 `tiering-kv:6379`，`status=UP / version=4.1.0` |
| 前端 | `http://127.0.0.1:8081` 返回 SPA，登录成功 |
| 接口端到端 | `node scripts/api-smoke.mjs --base http://127.0.0.1:8081` → **25/25 通过** |
| 界面 | `console-web/screenshots/docker/`（6 页截图，含实时曲线） |

## 两个构建期问题的处置

1. **Maven Central 返回 403**（本机网络被 CDN 限流）
   → 镜像内改用阿里云公共镜像：`maven-settings.xml`（本目录与
   `console-server/` 各一份），并用 BuildKit
   `--mount=type=cache,target=/root/.m2` 复用依赖缓存。

2. **浏览器同源访问被 Spring CORS 拒绝（403 Invalid CORS request）**
   → Nginx 反代时加 `proxy_set_header Origin "";`：浏览器到 Nginx
   已是同源，后端无需再判 CORS；同时保留 `CONSOLE_CORS` 配置，
   便于直连 8080 调试。

3. **控制面与数据面不在同一容器** → `127.0.0.1:6379` 不可达
   → 新增启动引导参数 `CONSOLE_BOOTSTRAP_INSTANCE=local-6379@tiering-kv:6379`，
   启动时把默认实例地址改写为容器网络地址（本机开发留空即可）。

## 生产化前需要覆盖的默认值

| 变量 | 默认 | 生产建议 |
| --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | `tk_root_pwd` | 强口令 + 独立业务账号 |
| `CONSOLE_JWT_SECRET` | 占位值 | ≥32 字节随机串 |
| `CONSOLE_BOOTSTRAP_INSTANCE` | 本机 | 指向真实数据面 |
