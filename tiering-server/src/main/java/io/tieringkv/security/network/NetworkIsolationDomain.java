package io.tieringkv.security.network;

/** 租户网络域（ADR-0161）：VPC/子网/私有网络标志。 */
public record NetworkIsolationDomain(String tenantId, String vpcId,
                                     String subnetId,
                                     boolean privateNetwork) {

    public NetworkIsolationDomain {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException(
                    "tenantId required");
        }
        if (vpcId == null || vpcId.isBlank()) {
            throw new IllegalArgumentException("vpcId required");
        }
        if (subnetId == null || subnetId.isBlank()) {
            throw new IllegalArgumentException("subnetId required");
        }
    }
}
