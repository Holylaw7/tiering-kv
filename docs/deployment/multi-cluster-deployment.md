# 多集群与多云部署（v4 M4）

## 拓扑模型

`TieringKVTopology`（deploy/operator/topology-sample.yaml）声明集群集合
与跨集群复制边：每条边 `from → to + regionId` 表示源集群 region 复制到
目标集群（M3 CrossClusterReplicationChannel / LWW 决策接线目标）。

```yaml
spec:
  clusters: [production, dr]
  replicationEdges:
    - {from: production, to: dr, regionId: r1}
```

计划语义（MultiClusterPlanner）：

- 期望边缺失 → CONNECT（创建复制通道）；
- 当前边多余 → DISCONNECT（拆除通道）；
- 收敛 → NOOP。

校验：集群非空、边端点必须在集群集合内、禁止自环。

## 多云部署参数

`MulticloudConfig`（ADR-0136）：storageClass / ingressClass / registry /
gatewayReplicas，按云提供商（云盘、入口控制器、镜像仓库）差异化配置；
示例见 deploy/operator/sample.yaml 与测试矩阵。

## 接线路径

```text
TieringKVTopology
      ↓ MultiClusterPlanner
CONNECT/DISCONNECT
      ↓
CrossClusterReplicationChannel（M3）
      ↓
目标端 CrossClusterSink（LWW + 水位）
```

## 故障切换演练

- 复制水位持久化保证跨重启续传；
- 分区恢复后重放幂等（M3 混沌覆盖）；
- 集群级切换演练脚本：scripts/upgrade-drill.sh / restore-drill.sh
  （维护基线）。
