package io.tieringkv.saas;

import io.tieringkv.operator.TieringKVClusterSpec;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 租户集群生成（ADR-0118）：配额内生成 Operator spec。 */
public final class TenantClusterPlanner {

    public TieringKVClusterSpec plan(ClusterTenant tenant,
                                     int storageReplicas,
                                     String image) {
        if (storageReplicas > tenant.maxRegions()) {
            throw new IllegalArgumentException(
                    "storage replicas exceed tenant region quota");
        }
        List<String> regions = IntStream.range(1, storageReplicas + 1)
                .mapToObj(i -> "r" + i).collect(Collectors.toList());
        return new TieringKVClusterSpec(3, storageReplicas, regions,
                image, "0 2 * * *", 168);
    }
}
