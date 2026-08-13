# Cross-Regulatory Federation

## 设计

跨监管域联邦仲裁（ADR-0256）在多组织联邦之上增加监管域边界：

```text
cloud → regulatory domain
    ↓
域内多数 cloud 合格 → 域合格
    ↓
全部参与域合格 → 跨域一阶段
    ↓
任一域不合格 → 回退 2PC（fallback2Pc=true）
```

## 关键行为

- `registerDomain(cloud, domain)`：监管域边界发现，变更使结果缓存失效；
- `registerZone(cloud, zone, eligible)`：区级资格，云内多数区合格 →
  云合格；
- `commit(txnId, clouds[, commitTs])`：幂等，返回
  `DomainResult(onePhase, fallback2Pc, domains, eligibleDomains, ...)`；
- 与 `MultiOrgFederationArbitration` / `GlobalUnifiedOnePhaseArbitration`
  联动：外部仲裁要求回退时，即使域级合格也回退 2PC；
- 一阶段提交推进 `ResolvedTimestampService` 水位。

## 一致性语义

跨监管域一阶段只有在全部参与域合格时允许；任一域不合格回退 2PC，
保证跨域提交不会绕过单域合规状态。结果缓存按 txnId + 拓扑版本 +
参与云集合计算，重复提交返回同一结果。

## 接入点

`io.tieringkv.transaction.async.CrossRegulatoryFederationArbitration`，
测试矩阵见 `CrossRegulatoryFederationArbitrationTest`（全部域合格组合 /
不合格回退 / 幂等 / 外部仲裁联动）。
