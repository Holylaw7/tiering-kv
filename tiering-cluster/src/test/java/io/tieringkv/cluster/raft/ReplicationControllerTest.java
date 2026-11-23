package io.tieringkv.cluster.raft;

import io.tieringkv.cluster.ReplicatedStorageEngine;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.startAll;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 自适应复制（ADR-0050）：控制器 + 异步提案 + 安全。 */
class ReplicationControllerTest {

    @Test
    void lowPendingSmallBatch() {
        ReplicationController controller = ReplicationController.defaults();
        assertThat(controller.batchSize()).isEqualTo(ReplicationController.MIN_BATCH);
    }

    @Test
    void highPendingLargeBatch() {
        ReplicationController controller = ReplicationController.defaults();
        controller.setPendingEntries(1024);
        assertThat(controller.batchSize()).isEqualTo(ReplicationController.MAX_BATCH);
    }

    @Test
    void batchClamped() {
        ReplicationController controller = ReplicationController.defaults();
        controller.setPendingEntries(10_000);
        assertThat(controller.batchSize()).isLessThanOrEqualTo(ReplicationController.MAX_BATCH);
        controller.setPendingEntries(-1);
        assertThat(controller.batchSize()).isGreaterThanOrEqualTo(ReplicationController.MIN_BATCH);
    }

    @Test
    void lowPendingLongFlush() {
        ReplicationController controller = ReplicationController.defaults();
        assertThat(controller.flushIntervalMillis())
                .isEqualTo(ReplicationController.MAX_FLUSH_MILLIS);
    }

    @Test
    void highPendingShortFlush() {
        ReplicationController controller = ReplicationController.defaults();
        controller.setPendingEntries(1024);
        assertThat(controller.flushIntervalMillis())
                .isEqualTo(ReplicationController.MIN_FLUSH_MILLIS);
    }

    @Test
    void followerLagShortensFlush() {
        ReplicationController controller = ReplicationController.defaults();
        long before = controller.flushIntervalMillis();
        controller.setFollowerLag(2048);
        assertThat(controller.flushIntervalMillis()).isLessThan(before);
    }

    @Test
    void rttAffectsBatch() {
        ReplicationController controller = ReplicationController.defaults();
        controller.setPendingEntries(256);
        controller.recordRttNanos(5_000_000);
        long withRtt = controller.batchSize();
        controller.recordRttNanos(100_000);
        assertThat(controller.batchSize()).isLessThanOrEqualTo((int) withRtt);
    }

    @Test
    void emaSmoothsRtt() {
        ReplicationController controller = new ReplicationController(0.5);
        controller.recordRttNanos(1_000_000);
        controller.recordRttNanos(100_000_000);
        controller.recordRttNanos(1_000_000);
        assertThat(controller.rttNanos()).isBetween(1_000_000d, 100_000_000d);
    }

    @Test
    void flushClamped() {
        ReplicationController controller = ReplicationController.defaults();
        controller.setPendingEntries(10_000);
        controller.setFollowerLag(10_000);
        assertThat(controller.flushIntervalMillis())
                .isGreaterThanOrEqualTo(ReplicationController.MIN_FLUSH_MILLIS);
    }

    @Test
    void negativeInputsClamped() {
        ReplicationController controller = ReplicationController.defaults();
        controller.setPendingEntries(-5);
        controller.setFollowerLag(-5);
        assertThat(controller.flushIntervalMillis())
                .isGreaterThanOrEqualTo(ReplicationController.MIN_FLUSH_MILLIS);
    }

    @Test
    void partialPendingBatchBetweenBounds() {
        ReplicationController controller = ReplicationController.defaults();
        controller.setPendingEntries(256);
        assertThat(controller.batchSize())
                .isBetween(ReplicationController.MIN_BATCH, ReplicationController.MAX_BATCH);
    }

    @Test
    void flushIntervalProportionalToPressure() {
        ReplicationController controller = ReplicationController.defaults();
        long low = controller.flushIntervalMillis();
        controller.setPendingEntries(128);
        long mid = controller.flushIntervalMillis();
        controller.setPendingEntries(512);
        long high = controller.flushIntervalMillis();
        assertThat(mid).isLessThan(low);
        assertThat(high).isLessThanOrEqualTo(mid);
    }

    @Test
    void rttEmaInitialValue() {
        ReplicationController controller = ReplicationController.defaults();
        controller.recordRttNanos(2_000_000);
        assertThat(controller.rttNanos()).isEqualTo(2_000_000d);
    }

    @Test
    void asyncPutSucceedsOnSoloLeader() throws Exception {
        List<String> applied = new ArrayList<>();
        ReplicatedStorageEngine engine = ReplicatedStorageEngine.create(
                "solo", List.of(), engineWith(applied),
                new LeaderElection(100, 80), 25, 10);
        engine.raft().start();
        try {
            awaitLeader(List.of(engine.raft()), 3000);
            engine.putAsync(bytes("k"), bytes("v")).get(5, TimeUnit.SECONDS);
            assertThat(applied).hasSize(1);
        } finally {
            engine.raft().close();
        }
    }

    @Test
    void asyncPutTimeoutOnSuspendedLeader() throws Exception {
        List<String> applied = new ArrayList<>();
        ReplicatedStorageEngine engine = ReplicatedStorageEngine.create(
                "solo", List.of(), engineWith(applied),
                new LeaderElection(100, 80), 25, 10);
        engine.raft().start();
        try {
            awaitLeader(List.of(engine.raft()), 3000);
            engine.raft().suspend();
            assertThatThrownBy(() -> engine.putAsync(bytes("k"), bytes("v"))
                    .orTimeout(200, TimeUnit.MILLISECONDS).join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class);
        } finally {
            engine.raft().close();
        }
    }

    @Test
    void proposeFutureCancellable() throws Exception {
        List<String> applied = new ArrayList<>();
        List<RaftNode> peers = new ArrayList<>();
        HoldingTransport holding = new HoldingTransport(peers);
        RaftNode[] nodes = new RaftNode[3];
        for (int i = 0; i < 3; i++) {
            String id = "n" + (i + 1);
            nodes[i] = new RaftNode(id, holding,
                    (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                    new LeaderElection(100, 80), 25, 10,
                    new MemoryRaftLog(), null, null,
                    RaftReplicationConfig.defaults(), ReplicationController.defaults());
        }
        peers.addAll(List.of(nodes));
        startAll(nodes);
        try {
            RaftNode leader = awaitLeader(List.of(nodes), 5000);
            CompletableFuture<Long> future = leader.propose(bytes("k"));
            assertThat(future.cancel(true)).isTrue();
            assertThat(future.isCancelled()).isTrue();
            holding.releaseAll();
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void asyncPutFailsOnFollowerAfterRetries() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode[] nodes = group3(applied);
        startAll(nodes);
        try {
            RaftNode leader = awaitLeader(List.of(nodes), 5000);
            RaftNode follower = nodes[0] == leader ? nodes[1] : nodes[0];
            ReplicatedStorageEngine followerEngine = ReplicatedStorageEngine.create(
                    follower.id(), List.of(), engineWith(applied),
                    new LeaderElection(100, 80), 25, 10);
            assertThatThrownBy(() -> followerEngine.putAsync(bytes("k"), bytes("v"))
                    .orTimeout(2000, TimeUnit.MILLISECONDS).join())
                    .isInstanceOf(java.util.concurrent.CompletionException.class);
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void manyAsyncPutsCompleteOnSolo() throws Exception {
        List<String> applied = new ArrayList<>();
        ReplicatedStorageEngine engine = ReplicatedStorageEngine.create(
                "solo", List.of(), engineWith(applied),
                new LeaderElection(100, 80), 25, 10);
        engine.raft().start();
        try {
            awaitLeader(List.of(engine.raft()), 3000);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                futures.add(engine.putAsync(bytes("k" + i), bytes("v")));
            }
            for (CompletableFuture<Void> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            assertThat(applied).hasSize(100);
        } finally {
            engine.raft().close();
        }
    }

    @Test
    void asyncPutOrderAppliedFifo() throws Exception {
        List<String> applied = new ArrayList<>();
        ReplicatedStorageEngine engine = ReplicatedStorageEngine.create(
                "solo", List.of(), engineWith(applied),
                new LeaderElection(100, 80), 25, 10);
        engine.raft().start();
        try {
            awaitLeader(List.of(engine.raft()), 3000);
            engine.putAsync(bytes("a"), bytes("1")).join();
            engine.putAsync(bytes("b"), bytes("2")).join();
            engine.putAsync(bytes("c"), bytes("3")).join();
            assertThat(applied).containsExactly("a", "b", "c");
        } finally {
            engine.raft().close();
        }
    }

    // ---------- helpers ----------

    private static io.tieringkv.storage.StorageEngine engineWith(List<String> applied) {
        return new io.tieringkv.storage.StorageEngine() {
            @Override
            public void put(byte[] key, byte[] value) {
                applied.add(new String(key, StandardCharsets.UTF_8));
            }

            @Override
            public void put(byte[] key, byte[] value, long ttlMillis) {
                put(key, value);
            }

            @Override
            public byte[] get(byte[] key) {
                return null;
            }

            @Override
            public boolean delete(byte[] key) {
                return false;
            }

            @Override
            public boolean exists(byte[] key) {
                return false;
            }

            @Override
            public io.tieringkv.storage.StorageIterator iterator() {
                return new io.tieringkv.storage.StorageIterator() {
                    @Override
                    public boolean hasNext() {
                        return false;
                    }

                    @Override
                    public io.tieringkv.storage.memory.KeyValueEntry next() {
                        throw new IllegalStateException();
                    }

                    @Override
                    public void close() {
                    }
                };
            }

            @Override
            public long size() {
                return 0;
            }
        };
    }

    private static RaftNode[] group3(List<String> applied) {
        RaftNode[] nodes = new RaftNode[3];
        List<RaftNode> peers = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            nodes[Integer.parseInt(id.substring(1)) - 1] = new RaftNode(
                    id, new LocalRaftTransport(peers, id), (index, command) ->
                            applied.add(new String(command, StandardCharsets.UTF_8)),
                    new LeaderElection(100, 80), 25, 10,
                    new MemoryRaftLog(), null, null,
                    RaftReplicationConfig.defaults(), ReplicationController.defaults());
        }
        peers.addAll(List.of(nodes));
        return nodes;
    }

    private static void closeAll(RaftNode[] nodes) {
        for (RaftNode node : nodes) {
            node.close();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 持有数据请求、即时放行心跳的测试传输。 */
    private static final class HoldingTransport implements RaftTransport {
        private final List<RaftNode> peers;
        private final List<Held> held = Collections.synchronizedList(new ArrayList<>());

        private HoldingTransport(List<RaftNode> peers) {
            this.peers = peers;
        }

        @Override
        public List<String> peerIds() {
            return peers.stream().map(RaftNode::id).toList();
        }

        @Override
        public CompletableFuture<VoteResponse> requestVote(String target, VoteRequest request) {
            return CompletableFuture.completedFuture(find(target).receive(request));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> appendEntries(
                String target, AppendEntriesRequest request) {
            if (request.entries().isEmpty()) {
                return CompletableFuture.completedFuture(find(target).receive(request));
            }
            CompletableFuture<AppendEntriesResponse> future = new CompletableFuture<>();
            held.add(new Held(target, request, future));
            return future;
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> installSnapshot(
                String target, InstallSnapshotRequest request) {
            return CompletableFuture.completedFuture(find(target).receive(request));
        }

        private void releaseAll() {
            List<Held> snapshot;
            synchronized (held) {
                snapshot = List.copyOf(held);
                held.clear();
            }
            for (Held item : snapshot) {
                item.future().complete(find(item.target()).receive(item.request()));
            }
        }

        private RaftNode find(String id) {
            for (RaftNode peer : peers) {
                if (peer.id().equals(id)) {
                    return peer;
                }
            }
            throw new IllegalStateException("no peer " + id);
        }

        private record Held(String target, AppendEntriesRequest request,
                            CompletableFuture<AppendEntriesResponse> future) {
        }
    }

}
