package io.tieringkv.replication.crdt;

/** LWW 寄存器（ADR-0114）：时间戳 + 节点优先级，后写胜出。 */
public final class LwwRegister {

    private long timestamp;
    private String node;
    private byte[] value;

    public synchronized void set(long timestamp, String node, byte[] value) {
        if (wins(timestamp, node)) {
            this.timestamp = timestamp;
            this.node = node;
            this.value = value == null ? null : value.clone();
        }
    }

    public synchronized void merge(LwwRegister other) {
        set(other.timestamp, other.node, other.value());
    }

    public synchronized byte[] value() {
        return value == null ? null : value.clone();
    }

    public synchronized long timestamp() {
        return timestamp;
    }

    public synchronized String node() {
        return node;
    }

    private boolean wins(long candidateTimestamp, String candidateNode) {
        if (candidateTimestamp != timestamp) {
            return candidateTimestamp > timestamp;
        }
        if (node == null) {
            return true;
        }
        return candidateNode != null
                && candidateNode.compareTo(node) > 0;
    }
}
