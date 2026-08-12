# 网络策略即代码指南（ADR-0169）

## DSL

```text
# 声明式策略
allow: t1 -> t2
deny: t1 -> t3
```

## 编译

```java
new PolicyCompiler().apply(policy, dsl);
```

- 非法动作 / 缺箭头 / 空租户 → 拒绝；
- 规则顺序执行（后写覆盖先写）；
- 重复编译幂等，白名单状态不变。
