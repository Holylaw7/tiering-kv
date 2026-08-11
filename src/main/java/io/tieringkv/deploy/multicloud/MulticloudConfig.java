package io.tieringkv.deploy.multicloud;

/** 多云部署参数（ADR-0136）。 */
public record MulticloudConfig(String storageClass, String ingressClass,
                               String registry, int gatewayReplicas) {

    public MulticloudConfig {
        if (storageClass == null || storageClass.isBlank()) {
            throw new IllegalArgumentException(
                    "storageClass required");
        }
    }
}
