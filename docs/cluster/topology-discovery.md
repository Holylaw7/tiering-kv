# 全球拓扑自发现指南（ADR-0211）

## 使用

```java
TopologyDiscovery discovery = new TopologyDiscovery(healthTimeout);
discovery.heartbeat(new Heartbeat("n1", "r1", "az-1", ts), now);
Map<String, Set<String>> byRegion = discovery.groupByRegion();
Map<String, Set<String>> byAz = discovery.groupByAz();
discovery.remove("n1"); // 故障剔除
```

心跳陈旧 → unhealthy；分组驱动拓扑感知自治。
