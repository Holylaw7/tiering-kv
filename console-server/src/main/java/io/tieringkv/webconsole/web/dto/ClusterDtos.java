package io.tieringkv.webconsole.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 分布式集群拓扑与 K8s Operator 桥接 DTO（v6.2.0 C2）。 */
public final class ClusterDtos {

    private ClusterDtos() {
    }

    public record RegionNodeView(
            int index,
            String regionId,
            String host,
            int port,
            int slotCount,
            double slotPercent) {
    }

    public record TransactionMetricsView(
            long beginTxn,
            long activeTxn,
            long committedTxn,
            long rollbackTxn,
            long conflictTxn,
            long abortTxn,
            long recoveryTxn,
            long lockCount,
            double commitLatencyMs,
            double prepareLatencyMs,
            long networkRetry,
            double lockWaitMs,
            long regionCount) {
    }

    public record PersistenceStatusView(
            boolean bgsaveInProgress,
            long lastSaveTime,
            String lastStatus,
            long lastDurationMillis,
            String lastError) {
    }

    public record ClusterTopologyView(
            Long instanceId,
            String instanceName,
            String mode,
            boolean distributed,
            String nodeId,
            String version,
            long totalKeys,
            int totalSlots,
            int regionCount,
            List<RegionNodeView> regions,
            TransactionMetricsView transaction,
            PersistenceStatusView persistence) {
    }

    public record K8sClusterSummary(
            String name,
            String namespace,
            String phase,
            String image,
            int metadataReplicas,
            int readyMetadata,
            int storageReplicas,
            int readyStorage,
            int gatewayReplicas,
            int readyGateway,
            List<String> regionIds,
            Long generation,
            Long observedGeneration,
            String lastAction,
            String lastBackupJob,
            String lastBackupStatus,
            String lastBackupTime,
            String backupScheduleCron,
            String message,
            String createdAt) {
    }

    public record K8sClusterListView(
            boolean enabled,
            String namespace,
            String apiServer,
            String message,
            List<K8sClusterSummary> clusters) {
    }

    public record K8sScaleRequest(
            @Min(value = 1, message = "存储节点数至少为 1") @Max(value = 32, message = "存储节点数不能超过 32")
            Integer storageReplicas,
            @Min(value = 1, message = "网关节点数至少为 1") @Max(value = 16, message = "网关节点数不能超过 16")
            Integer gatewayReplicas,
            @Min(value = 1, message = "元数据节点数至少为 1") @Max(value = 7, message = "元数据节点数不能超过 7")
            Integer metadataReplicas) {
    }

    public record K8sUpgradeRequest(
            @NotBlank(message = "目标镜像不能为空") @Size(max = 256, message = "镜像地址长度不能超过 256")
            String image) {
    }

    public record K8sBackupTriggerResult(
            String clusterName,
            String namespace,
            String requestId,
            String message) {
    }
}
