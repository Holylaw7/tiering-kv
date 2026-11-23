package io.tieringkv.replication.active;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.replication.ReplicaSink;
import io.tieringkv.replication.VersionVector;
import io.tieringkv.replication.crdt.LwwRegister;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 全球 Active-Active 管道（ADR-0135）：多地域写 + 环回抑制 + 冲突合并。 */
public final class ActiveActivePipeline {

    private final List<ReplicaSink> peers;
    private final String nodeId;
    private final long timeoutMillis;
    private final VersionVector vector = new VersionVector();
    private final Map<String, LwwRegister> registers =
            new ConcurrentHashMap<>();
    private final AtomicLong localVersion = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();
    private final ConflictMetrics metrics = new ConflictMetrics();

    public ActiveActivePipeline(List<ReplicaSink> peers, String nodeId,
                                long timeoutMillis) {
        this.peers = List.copyOf(peers);
        this.nodeId = nodeId;
        this.timeoutMillis = timeoutMillis;
    }

    public CompletableFuture<Boolean> write(byte[] key, byte[] value) {
        long version = localVersion.incrementAndGet();
        vector.observe(nodeId, version);
        apply(key, value, nodeId, version);
        ChangeEvent event = new ChangeEvent(version,
                ChangeEvent.EventType.PUT, key, value, false,
                "aa-" + nodeId, nodeId, System.currentTimeMillis());
        if (peers.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Void>[] futures = peers.stream()
                .map(peer -> peer.apply(event))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures)
                .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .handle((ignored, error) -> error == null);
    }

    public void receive(byte[] key, byte[] value, String origin,
                        long version) {
        if (vector.seen(origin, version)) {
            suppressed.incrementAndGet();
            return;
        }
        vector.observe(origin, version);
        apply(key, value, origin, version);
    }

    public byte[] get(byte[] key) {
        LwwRegister register = registers.get(keyString(key));
        return register == null ? null : register.value();
    }

    public long suppressedCount() {
        return suppressed.get();
    }

    public ConflictMetrics metrics() {
        return metrics;
    }

    public VersionVector vector() {
        return vector;
    }

    private void apply(byte[] key, byte[] value, String node,
                       long version) {
        String stringKey = keyString(key);
        LwwRegister register = registers.computeIfAbsent(stringKey,
                ignored -> new LwwRegister());
        LwwRegister before = new LwwRegister();
        before.set(register.timestamp(), register.node(),
                register.value());
        register.set(version, node, value);
        if (before.node() != null && !before.node().equals(node)) {
            metrics.recordConflict();
        }
    }

    private static String keyString(byte[] key) {
        return new String(key,
                java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
