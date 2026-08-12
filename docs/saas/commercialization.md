# SaaS 商业化指南（ADR-0146）

## 组件

- `Subscription`：TRIAL → ACTIVE → CANCELED 状态机（不可变快照）；
- `MarketplaceCatalog`：集群模板 + 计费计划注册；
- `BillingSubscription`：订阅生命周期 + 周期结算联动。

## 结算语义

- ACTIVE：周期 roll 出 Invoice，订阅 cycle +1；
- TRIAL：免单并重置计量；
- CANCELED：拒绝继续结算。

## 示例

```java
BillingSubscription subscriptions =
        new BillingSubscription(billing, catalog);
subscriptions.subscribe("t1", "p1", true);
subscriptions.activate("t1");
Optional<Invoice> invoice = subscriptions.roll("t1", 0);
subscriptions.cancel("t1");
```
