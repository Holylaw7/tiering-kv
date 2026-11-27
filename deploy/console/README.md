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
| `MYSQL_SSL_MODE` | dev:`PREFERRED` / prod:`REQUIRED` | 生产建议 `REQUIRED` 或 `VERIFY_CA` / `VERIFY_IDENTITY` |
| `CONSOLE_JWT_SECRET` | 占位值 | ≥32 字节随机串 |
| `CONSOLE_BOOTSTRAP_INSTANCE` | 本机 | 指向真实数据面 |
| `CONSOLE_WEBHOOK_BLOCK_PRIVATE_URLS` | dev:`false` / prod:`true` | 生产必须保持 `true`（IP 固定直连防 DNS Rebinding） |
| `CONSOLE_WEBHOOK_ALLOWED_CIDRS` | 空 | 默认留空；仅在私有化网关必需时配置精确 IP/极小网段 |

## Webhook 出站安全与运维权限边界

1. **权限模型分离**：`console.security.webhook-allowed-cidrs`（`CONSOLE_WEBHOOK_ALLOWED_CIDRS`）属于**服务端部署配置**，没有任何 REST 接口允许修改，变更必须纳入部署配置审计；`OPS` 角色仅能管理告警渠道地址，无法扩大服务端出站白名单。
2. **默认拒绝与最小放行**：生产环境默认拦截环回、私网（RFC 1918）、CGNAT（含 `100.100.100.200`）、链路本地与云元数据（`169.254.169.254`）及 IPv6 隧道地址；确有内网飞书/企微网关需求时，仅放行精确网关 IP（`/32`）或极小网段，**严禁配置过宽私网网段（如 `10.0.0.0/8`、`172.16.0.0/12`、`0.0.0.0/0`）**，`ProductionConfigGuard` 启动期会对 `/12` 及以上大网段输出告警。
3. **白名单不豁免其他防线**：白名单仅允许命中目标通过私网拦截，不豁免 IP 固定建连（防 DNS Rebinding TOCTOU）、HTTP 3xx 重定向禁止、TLS SNI / X.509 证书校验以及云元数据保护（主机名白名单绝不允许解析到云元数据段）。
4. **纵深隔离与高可用落地**：
   - 应用层防护与 K8s `NetworkPolicy`（`deploy/helm/tiering-kv/templates/networkpolicy.yaml`）形成双层纵深隔离，可通过 `scripts/verify-egress-isolation.sh` 实测验证公网放行 / 私网拦截 / 云元数据拦截 / DNS 异常 fail-closed 四类场景；
   - 告警持续计时与静默窗口已下沉 MySQL `alert_eval_state`（Flyway `V13`），并通过原子条件更新保证多副本 HA 下不重复发送。
