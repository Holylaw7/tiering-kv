# Tiering-KV 可观测资产包（Prometheus + Grafana）

本目录提供与 Tiering-KV 数据面管理端口（`:9100/metrics`）及控制面 Micrometer 端点（`:8080/actuator/prometheus`）开箱即用对接的指标采集示例、告警规则与 Grafana 看板。

## 1. 暴露指标总览

| 指标名 | 来源 | 标签 | 说明 |
| :--- | :--- | :--- | :--- |
| `tieringkv_instance_up` | Console | `instance_id`, `instance_name` | 被纳管实例可达性（`1=UP` / `0=DOWN`） |
| `tieringkv_instance_memory_used_bytes` | Console | `instance_id`, `instance_name` | 热层已用内存字节数 |
| `tieringkv_instance_memory_ratio` | Console | `instance_id`, `instance_name` | 热层内存水位占比（`[0..1]`） |
| `tieringkv_instance_ops_per_sec` | Console | `instance_id`, `instance_name` | 实例实时 QPS |
| `tieringkv_instance_keys_total` | Console | `instance_id`, `instance_name` | 实例键总数 |
| `tieringkv_instance_cold_tables` | Console | `instance_id`, `instance_name` | 冷层 SSTable 文件数 |
| `tieringkv_instance_p99_latency_ms` | Console | `instance_id`, `instance_name` | 命令 P99 延迟（ms） |
| `tieringkv_console_pool_{active,idle,max}` | Console | — | 控制面 RESP 连接池活跃/空闲/容量水位 |
| `tieringkv_console_pool_borrow_timeouts_total` | Console | — | RESP 连接池累计借用超时次数 |
| `tieringkv_console_alerts_firing` | Console | — | 控制台当前触发中（`FIRING`）告警数 |
| `tieringkv_console_backups_total` | Console | `status` | 逻辑备份执行计数（`SUCCESS`/`FAILED`） |
| `tieringkv_console_restores_total` | Console | `status` | 逻辑恢复执行计数（`SUCCESS`/`FAILED`） |
| `tieringkv_console_audit_events_total` | Console | `action`, `success` | 审计操作事件计数（含 `BACKUP_VERIFY` 等） |

## 2. Prometheus 抓取配置示例

> 控制面 `/actuator/prometheus` 受 `ROLE_ADMIN` JWT 保护，请为监控抓取准备专用 `ADMIN` 账号并定期轮换 Bearer Token（或通过内网 Sidecar 注入）。数据面 `:9100/metrics` 面向集群内网免认证暴露。

```yaml
rule_files:
  - /etc/prometheus/rules/prometheus-alerts.yaml

scrape_configs:
  - job_name: tiering-kv-core
    scrape_interval: 15s
    metrics_path: /metrics
    static_configs:
      - targets: ["tiering-kv:9100"]

  - job_name: tiering-kv-console
    scrape_interval: 15s
    metrics_path: /actuator/prometheus
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/secrets/console-admin-jwt
    static_configs:
      - targets: ["console-server:8080"]
```

## 3. Grafana 看板导入

1. 打开 Grafana -> **Dashboards -> New -> Import**；
2. 上传 [grafana-dashboard.json](./grafana-dashboard.json)；
3. 选择已接入上述 Prometheus 的数据源即可实时查看热层水位、冷层 SSTable、吞吐/P99、RESP 连接池与备份/审计状态。

## 4. Kubernetes / Helm 一键启用

配合 [deploy/helm/tiering-kv](../helm/tiering-kv)（集群已安装 Prometheus Operator 时）：

```bash
helm upgrade --install tk deploy/helm/tiering-kv \
  --namespace tiering-kv --create-namespace \
  --set consoleServer.replicaCount=2 \
  --set consoleServer.backup.storageType=s3 \
  --set observability.serviceMonitor.enabled=true \
  --set observability.prometheusRule.enabled=true
```
