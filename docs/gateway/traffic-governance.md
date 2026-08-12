# 全球流量治理指南（ADR-0149）

## 组件

- `RegionQuota`：地域周期配额（CAS 原子获取）；
- `PriorityRouter`：配额不足时 LOW 丢弃、NORMAL/HIGH 降级备用地域；
- `TrafficPolicy`：优先级 QPS/配额占比映射。

## 路由语义

```text
key → RegionAffinityRouter → preferred
  preferred 有配额 → 接受
  无配额：
    LOW / 禁降级 → 拒绝
    NORMAL/HIGH + 降级 → 备用地域（degraded=true）
```

## 运维

- 周期调用 `quota.resetCycle()` / `policy.reset()`；
- 配额与占比参数化配置，禁止魔法数字。
