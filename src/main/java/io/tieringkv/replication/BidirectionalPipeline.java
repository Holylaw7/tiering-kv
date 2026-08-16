package io.tieringkv.replication;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.observability.ReplicationMetricsRegistry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 双向复制管道（ADR-0114）：版本向量环回抑制 + LWW 冲突收敛。
 * 事件携带 origin 节点与版本；已见事件不重复应用。
 */
public final class BidirectionalPipeline {

    private final List<ReplicaSink> peers;
    private final String nodeId;
    private final long syncTimeoutMillis;
    private final VersionVector vector = new VersionVector();
    private final AtomicLong localVersion = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();
    private final ReplicationMetricsRegistry metrics;
    private final java.util.Map<String, LwwState> lww =
            new ConcurrentHashMap<>();

    private record LwwState(long timestamp, String node, byte[] value) {
    }

    public BidirectionalPipeline(List<ReplicaSink> peers, String nodeId,
                                 long syncTimeoutMillis) {
        this(peers, nodeId, syncTimeoutMillis, null);
    }

    /** 可观测性喂数（ADR-0345）：可选复制指标注册表（additive）。 */
    public BidirectionalPipeline(List<ReplicaSink> peers, String nodeId,
                                 long syncTimeoutMillis,
                                 ReplicationMetricsRegistry metrics) {
        this.peers = List.copyOf(peers);
        this.nodeId = nodeId;
        this.syncTimeoutMillis = syncTimeoutMillis;
        this.metrics = metrics;
    }

    /** 本地写入：推进版本并向全部 peer 广播。 */
    public CompletableFuture<Boolean> write(byte[] key, byte[] value) {
        long version = localVersion.incrementAndGet();
        vector.observe(nodeId, version);
        apply(key, value, nodeId, version);
        ChangeEvent event = new ChangeEvent(version,
                ChangeEvent.EventType.PUT, key, value, false,
                "crdt-" + nodeId, nodeId, System.currentTimeMillis());
        return broadcast(event, nodeId, version)
                .whenComplete((ok, error) -> {
                    if (metrics != null && ok != null && ok) {
                        metrics.recordReplicated();
                    }
                });
    }

    /** 远端事件到达：环回抑制 + 冲突合并。 */
    public void receive(byte[] key, byte[] value, String origin,
                        long version) {
        if (vector.seen(origin, version)) {
            suppressed.incrementAndGet();
            if (metrics != null) {
                metrics.recordSuppressed();
            }
            return;
        }
        vector.observe(origin, version);
        LwwState previous = lww.get(keyString(key));
        if (previous != null && previous.node().equals(origin)
                && previous.timestamp() >= version) {
            suppressed.incrementAndGet();
            if (metrics != null) {
                metrics.recordSuppressed();
            }
            return;
        }
        apply(key, value, origin, version);
    }

    public byte[] get(byte[] key) {
        LwwState state = lww.get(keyString(key));
        return state == null ? null : state.value().clone();
    }

    public long suppressedCount() {
        return suppressed.get();
    }

    public long conflictsCount() {
        return conflicts.get();
    }

    public VersionVector vector() {
        return vector;
    }

    private void apply(byte[] key, byte[] value, String node,
                       long version) {
        String stringKey = keyString(key);
        LwwState previous = lww.get(stringKey);
        if (previous != null && !previous.node().equals(node)) {
            conflicts.incrementAndGet();
            if (metrics != null) {
                metrics.recordConflict();
            }
        }
        if (previous == null || wins(version, node, previous)) {
            lww.put(stringKey, new LwwState(version, node,
                    value == null ? null : value.clone()));
        }
    }

    private boolean wins(long version, String node, LwwState previous) {
        if (version != previous.timestamp()) {
            return version > previous.timestamp();
        }
        return node.compareTo(previous.node()) > 0;
    }

    private CompletableFuture<Boolean> broadcast(ChangeEvent event,
                                                 String origin,
                                                 long version) {
        if (peers.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Void>[] futures = peers.stream()
                .map(peer -> peer.apply(event)).toArray(
                        CompletableFuture[]::new);
        return CompletableFuture.allOf(futures)
                .orTimeout(syncTimeoutMillis, TimeUnit.MILLISECONDS)
                .handle((ignored, error) -> error == null);
    }

    private static String keyString(byte[] key) {
        return new String(key,
                java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
