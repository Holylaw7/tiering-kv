package io.tieringkv.operator;

/** TieringKVCluster 当前状态（ADR-0107）：reconcile 输入。 */
public record TieringKVClusterStatus(int readyMetadata,
                                     int readyStorage,
                                     int readyGateway,
                                     long observedGeneration,
                                     String lastAction) {
}
