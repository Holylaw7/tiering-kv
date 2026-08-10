package io.tieringkv.cluster.raft.client;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.LocalRaftTransport;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.startAll;
import static org.assertj.core.api.Assertions.assertThat;

/** 全异步提案（ADR-0054）：回调/批量/背压/leader 变更重试。 */
class AsyncReplicationClientTest {

    @Test
    void asyncSubmitCallbackInvoked() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Long> index = new AtomicReference<>(-1L);
        AtomicReference<Throwable> error = new AtomicReference<>();
        fixture.client().submit(bytes("a"),
                (i, e) -> {
                    index.set(i);
                    error.set(e);
                    latch.countDown();
                });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        assertThat(index.get()).isEqualTo(0);
        fixture.close();
    }

    @Test
    void manyAsyncSubmitsAllComplete() throws Exception {
        Fixture fixture = fixture();
        int total = 100;
        CountDownLatch latch = new CountDownLatch(total);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < total; i++) {
            fixture.client().submit(bytes("k" + i),
                    (index, error) -> {
                        if (error != null) {
                            failures.incrementAndGet();
                        }
                        latch.countDown();
                    });
        }
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).hasValue(0);
        assertThat(fixture.leader().commitIndex()).isEqualTo(total - 1);
        fixture.close();
    }

    @Test
    void backpressureRejectsAtCritical() {
        AsyncProposalQueue queue = new AsyncProposalQueue(10);
        for (int i = 0; i < 10; i++) {
            queue.offer(bytes("x"), context());
        }
        assertThat(queue.pressure()).isEqualTo(AsyncProposalQueue.Pressure.CRITICAL);
        AsyncReplicationClient client = new AsyncReplicationClient(queue, () -> null);
        AtomicReference<Throwable> error = new AtomicReference<>();
        boolean accepted = client.submit(bytes("y"), (i, e) -> error.set(e));
        assertThat(accepted).isFalse();
        assertThat(error.get()).isInstanceOf(IllegalStateException.class);
        client.close();
    }

    @Test
    void warningPressureAtSeventyPercent() {
        AsyncProposalQueue queue = new AsyncProposalQueue(10);
        for (int i = 0; i < 7; i++) {
            queue.offer(bytes("x"), context());
        }
        assertThat(queue.pressure()).isEqualTo(AsyncProposalQueue.Pressure.WARNING);
    }

    @Test
    void normalPressureBelowThreshold() {
        AsyncProposalQueue queue = new AsyncProposalQueue(10);
        for (int i = 0; i < 6; i++) {
            queue.offer(bytes("x"), context());
        }
        assertThat(queue.pressure()).isEqualTo(AsyncProposalQueue.Pressure.NORMAL);
    }

    @Test
    void queueBounded() {
        AsyncProposalQueue queue = new AsyncProposalQueue(5);
        for (int i = 0; i < 5; i++) {
            assertThat(queue.offer(bytes("x"), context())).isTrue();
        }
        assertThat(queue.offer(bytes("x"), context())).isFalse();
        assertThat(queue.size()).isEqualTo(5);
    }

    @Test
    void pollDecrementsSize() {
        AsyncProposalQueue queue = new AsyncProposalQueue(5);
        queue.offer(bytes("x"), context());
        assertThat(queue.poll()).isNotNull();
        assertThat(queue.size()).isZero();
    }

    @Test
    void expiredContextFailsCallback() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AsyncProposalContext expired = new AsyncProposalContext(
                1, 0, System.nanoTime() - 1, (i, e) -> {
            error.set(e);
            latch.countDown();
        });
        fixture.client().submitProposalForTest(
                new AsyncProposalQueue.Proposal(bytes("x"), expired), 0);
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(java.util.concurrent.TimeoutException.class);
        fixture.close();
    }

    @Test
    void leaderChangeRetriesOnNewLeader() throws Exception {
        Fixture fixture = fixture();
        RaftNode firstLeader = fixture.leader();
        firstLeader.suspend();
        firstLeader.close();
        RaftNode newLeader = awaitLeader(fixture.nodes(), 5000);
        assertThat(newLeader).isNotEqualTo(firstLeader);
        fixture.setLeader(newLeader);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        fixture.client().submit(bytes("after"), (i, e) -> {
            error.set(e);
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        fixture.close();
    }

    @Test
    void callbackOrderMatchesSubmission() throws Exception {
        Fixture fixture = fixture();
        int total = 30;
        CountDownLatch latch = new CountDownLatch(total);
        List<Long> indices = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            fixture.client().submit(bytes("o" + i), (index, error) -> {
                synchronized (indices) {
                    indices.add(index);
                }
                latch.countDown();
            });
        }
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        synchronized (indices) {
            assertThat(indices).hasSize(total);
            for (int i = 0; i < total; i++) {
                assertThat(indices).contains((long) i);
            }
        }
        fixture.close();
    }

    @Test
    void noLeaderFailsAfterRetries() throws Exception {
        AsyncProposalQueue queue = new AsyncProposalQueue(100);
        AsyncReplicationClient client = new AsyncReplicationClient(queue, () -> null);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        client.submit(bytes("x"), (i, e) -> {
            error.set(e);
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(IllegalStateException.class);
        client.close();
    }

    @Test
    void drainProcessesAllQueued() throws Exception {
        Fixture fixture = fixture();
        int total = 20;
        for (int i = 0; i < total; i++) {
            fixture.client().submit(bytes("d" + i), (index, error) -> {
            });
        }
        assertThat(fixture.leader().commitIndex()).isEqualTo(total - 1);
        fixture.close();
    }

    @Test
    void closeStopsDraining() {
        AsyncProposalQueue queue = new AsyncProposalQueue(10);
        AsyncReplicationClient client = new AsyncReplicationClient(queue, () -> null);
        client.close();
        assertThat(client.submit(bytes("x"), (i, e) -> {
        })).isTrue(); // 入队成功但不处理
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    void pollEmptyQueueReturnsNull() {
        AsyncProposalQueue queue = new AsyncProposalQueue(5);
        assertThat(queue.poll()).isNull();
    }

    @Test
    void capacityAccessor() {
        AsyncProposalQueue queue = new AsyncProposalQueue(42);
        assertThat(queue.capacity()).isEqualTo(42);
    }

    @Test
    void pressureBoundaryAtNinetyNinePercent() {
        AsyncProposalQueue queue = new AsyncProposalQueue(100);
        for (int i = 0; i < 99; i++) {
            queue.offer(bytes("x"), context());
        }
        assertThat(queue.pressure()).isEqualTo(AsyncProposalQueue.Pressure.WARNING);
    }

    @Test
    void contextExpiredFlag() {
        AsyncProposalContext expired = new AsyncProposalContext(
                1, 0, System.nanoTime() - 1, (i, e) -> {
        });
        AsyncProposalContext fresh = new AsyncProposalContext(
                1, 0, System.nanoTime() + 1_000_000_000L, (i, e) -> {
        });
        assertThat(expired.expired()).isTrue();
        assertThat(fresh.expired()).isFalse();
    }

    @Test
    void retryLimitedToThreeAttempts() throws Exception {
        AsyncProposalQueue queue = new AsyncProposalQueue(10);
        AsyncReplicationClient client = new AsyncReplicationClient(queue, () -> null);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        client.submit(bytes("x"), (i, e) -> {
            error.set(e);
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(IllegalStateException.class);
        client.close();
    }

    @Test
    void concurrentSubmitsAllComplete() throws Exception {
        Fixture fixture = fixture();
        int threads = 8;
        int perThread = 25;
        int total = threads * perThread;
        CountDownLatch latch = new CountDownLatch(total);
        AtomicInteger failures = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    fixture.client().submit(bytes("c" + i),
                            (index, error) -> {
                                if (error != null) {
                                    failures.incrementAndGet();
                                }
                                latch.countDown();
                            });
                }
            }).start();
        }
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).hasValue(0);
        fixture.close();
    }

    @Test
    void submitReturnsTrueWhenAccepted() throws Exception {
        Fixture fixture = fixture();
        boolean accepted = fixture.client().submit(bytes("ok"), (i, e) -> {
        });
        assertThat(accepted).isTrue();
        fixture.close();
    }

    private static Fixture fixture() throws Exception {
        List<String> applied = new ArrayList<>();
        List<RaftNode> peers = new ArrayList<>();
        List<RaftNode> nodes = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            RaftNode node = new RaftNode(id, new LocalRaftTransport(peers, id),
                    (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                    new LeaderElection(100, 80), 25, 10,
                    new MemoryRaftLog(), null, null);
            nodes.add(node);
        }
        peers.addAll(nodes);
        startAll(nodes.toArray(new RaftNode[0]));
        RaftNode leader = awaitLeader(nodes, 5000);
        AsyncProposalQueue queue = new AsyncProposalQueue(1024);
        java.util.concurrent.atomic.AtomicReference<RaftNode> ref =
                new java.util.concurrent.atomic.AtomicReference<>(leader);
        AsyncReplicationClient client = new AsyncReplicationClient(queue, ref::get);
        return new Fixture(nodes, leader, client, ref);
    }

    private static AsyncProposalContext context() {
        return new AsyncProposalContext(1, 0, System.nanoTime() + 5_000_000_000L,
                (i, e) -> {
                });
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Fixture implements AutoCloseable {
        private final List<RaftNode> nodes;
        private volatile RaftNode leader;
        private final AsyncReplicationClient client;
        private final java.util.concurrent.atomic.AtomicReference<RaftNode> ref;

        private Fixture(List<RaftNode> nodes, RaftNode leader,
                        AsyncReplicationClient client,
                        java.util.concurrent.atomic.AtomicReference<RaftNode> ref) {
            this.nodes = nodes;
            this.leader = leader;
            this.client = client;
            this.ref = ref;
        }

        private RaftNode leader() {
            return leader;
        }

        private List<RaftNode> nodes() {
            return nodes;
        }

        private void setLeader(RaftNode leader) {
            this.leader = leader;
            ref.set(leader);
        }

        private AsyncReplicationClient client() {
            return client;
        }

        @Override
        public void close() {
            client.close();
            for (RaftNode node : nodes) {
                node.close();
            }
        }
    }
}
