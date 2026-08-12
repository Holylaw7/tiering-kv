# 自治 PD 调度指南（ADR-0211）

## 使用

```java
AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
        maxMovesPerRound);
ScheduleResult result = scheduler.execute(move);
scheduler.openCircuit(reason); // 熔断
scheduler.newRound();          // 周期重置
```

护栏：单轮上限 + 熔断；执行可审计。
