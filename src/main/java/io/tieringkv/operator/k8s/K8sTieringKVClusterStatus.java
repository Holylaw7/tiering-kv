package io.tieringkv.operator.k8s;

/** K8s CRD status（ADR-0322 M4 增强）：reconcile 输出。 */
public final class K8sTieringKVClusterStatus {

    private int readyMetadata;
    private int readyStorage;
    private int readyGateway;
    private long observedGeneration;
    private String lastAction;
    private String phase;

    public int getReadyMetadata() {
        return readyMetadata;
    }

    public void setReadyMetadata(int readyMetadata) {
        this.readyMetadata = readyMetadata;
    }

    public int getReadyStorage() {
        return readyStorage;
    }

    public void setReadyStorage(int readyStorage) {
        this.readyStorage = readyStorage;
    }

    public int getReadyGateway() {
        return readyGateway;
    }

    public void setReadyGateway(int readyGateway) {
        this.readyGateway = readyGateway;
    }

    public long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    public String getLastAction() {
        return lastAction;
    }

    public void setLastAction(String lastAction) {
        this.lastAction = lastAction;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }
}
