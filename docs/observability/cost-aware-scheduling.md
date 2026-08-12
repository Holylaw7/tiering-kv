# 多云成本竞价调度指南（ADR-0168）

## 约束

- 数据主权：候选云驻留要求必须等于任务所需驻留；
- 配额：候选云可用配额 ≥ 任务所需配额；
- SLO：任务要求 SLO 时候选云必须 meetsSlo；
- 目标：满足全部约束的最小单价云。

## 使用

```java
Optional<SchedulingDecision> decision = scheduler.schedule(
        new ScheduleTask("t1", "us", 10, true),
        List.of(new CloudOption("aws-us", 5, 100, true),
                new CloudOption("gcp-us", 3, 100, true)),
        policy);
```

无满足候选返回空，禁止违约调度。
