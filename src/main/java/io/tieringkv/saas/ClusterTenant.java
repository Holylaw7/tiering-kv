package io.tieringkv.saas;

/** SaaS 租户模型（ADR-0113）：配额约束。 */
public record ClusterTenant(String tenantId, String clusterName,
                            int maxRegions, int maxStorageGB) {

    public ClusterTenant {
        if (maxRegions < 1 || maxStorageGB < 1) {
            throw new IllegalArgumentException("quota must be >= 1");
        }
    }
}
