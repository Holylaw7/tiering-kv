package io.tieringkv.saas;

/** 市场规格模板（ADR-0124）：规格 → 定价。 */
public record ClusterTemplate(String templateId, int metadataReplicas,
                              int storageReplicas, int maxStorageGB,
                              double monthlyPrice) {
}
