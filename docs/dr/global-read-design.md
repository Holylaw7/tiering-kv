# 全球一致性读

Phase 29 · ADR-0123

## 模式

| 模式 | 语义 | 读位置 |
| --- | --- | --- |
| STRONG | readTS <= 本地水位 | 仅本地新鲜 |
| BOUNDED | readTS <= 已复制水位 | 就近可读 |

## 路由

`GlobalReadRouter.route(preferred, requiredSeq)`：STRONG 要求本地水位
达标；BOUNDED 允许复制水位兜底；陈旧时返回 null（客户端重定向）。

## 基准（进程内）

全局读路由 0.1–1M ops/s。

## 限制

- 水位由复制管道维护（Phase 30 联动真实复制）；
- 跨地域延迟待 CI。
