# SaaS 仪表盘指南（ADR-0150）

## 视图

| 视图 | 权限 | 内容 |
| --- | --- | --- |
| dashboard | READ | 订阅总数 + 租户状态/周期表 |
| marketplace | READ | 计划/模板目录 + 自服务下单表单 |
| subscriptions | READ | 租户 × 计划 × 状态 × 周期 |

## API

```java
SaasConsoleApi api = new SaasConsoleApi(subscriptions, catalog,
        credentials);
api.subscribe(admin, "t1", "p1", false);
api.roll(admin, "t1");          // 周期账单（TRIAL 免单）
api.status(reader, "t1");       // 只读可查
```

动作（订阅/激活/取消/计费）要求 ADMIN；查询要求 READ。
