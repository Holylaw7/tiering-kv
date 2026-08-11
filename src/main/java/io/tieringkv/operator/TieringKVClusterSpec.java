package io.tieringkv.operator;

import java.util.List;

/** TieringKVCluster 期望状态（ADR-0107）：声明式集群拓扑。 */
public record TieringKVClusterSpec(int metadataReplicas,
                                   int storageReplicas,
                                   List<String> regionIds,
                                   String image,
                                   String backupScheduleCron,
                                   long backupRetentionHours) {

    public TieringKVClusterSpec {
        regionIds = List.copyOf(regionIds);
    }
}
