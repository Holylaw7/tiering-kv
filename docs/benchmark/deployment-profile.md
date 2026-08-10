# 生产部署画像（Deployment Profile）

基线规格（可按容量模型缩放）：

```text
CPU：8 核起步；单节点基线 8C16G
内存：16G（-Xmx 50% = 8G）
```

## JVM

```text
-Xmx8g -XX:MaxDirectMemorySize=512m -XX:+UseG1GC
-XX:MaxGCPauseMillis=100
JFR：生产按需 -XX:StartFlightRecording=...,maxsize=256m
```

## 运行时

```text
KeyShardExecutor：min(16, 核数)（20 核实测 20 分片）
TierWorkerPool：1 flush + 2 migration（daemon）
WAL：EVERY_SEC（丢失窗口 ≤1s）；强一致 ALWAYS
BlockCache：1024 块（≈8MB，off-heap）；HotKey：窗口 1000ms / 阈值 1000 / TTL 500ms
水位：70 / 85 / 95；背压超时 1000ms
```

## 目录与运维

```text
独立数据目录：./data/{wal,cold,migration}；独立日志；独立压测客户端进程
上线回归：Workload A/B/C 吞吐与 P99；容量模型季度校准
```

预期（基线规格）：全链路 60–90K ops/s（8 核，含协议/网络）；P99 <5ms。
