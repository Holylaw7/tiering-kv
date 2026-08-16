package io.tieringkv.cluster;

import io.tieringkv.cluster.gateway.RedisClusterGateway;
import io.tieringkv.cluster.raft.LocalRaftTransport;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static io.tieringkv.cluster.RaftTestSupport.ELECTION;
import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 复制原子字符串操作（ADR-0352，TD-081 关闭）：
 * ReplicatedStorageEngine 经 Raft ATOMIC 命令提供确定性原子语义，
 * 结果回传 Leader 调用方；覆盖复制一致性、网关端到端、Leader
 * 切换、日志重放与不支持引擎显式失败。
 */
class ReplicatedAtomicOpsTest {

    @TempDir
    Path dir;

    @Test
    void incrementReplicatesAndReturnsResult() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            leader.put(bytes("k"), bytes("5"));
            awaitReplicated(cluster, "k", bytes("5"));
            assertThat(leader.increment(bytes("k"), 3)).isEqualTo(8L);
            awaitReplicated(cluster, "k", bytes("8"));
        } finally {
            cluster.close();
        }
    }

    @Test
    void appendReplicatesWithLength() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            leader.put(bytes("k"), bytes("ab"));
            awaitReplicated(cluster, "k", bytes("ab"));
            assertThat(leader.append(bytes("k"), bytes("cd"))).isEqualTo(4);
            awaitReplicated(cluster, "k", bytes("abcd"));
        } finally {
            cluster.close();
        }
    }

    @Test
    void getSetReturnsOldAndClearsTtl() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            leader.put(bytes("k"), bytes("v"), 100_000);
            awaitReplicated(cluster, "k", bytes("v"));
            assertThat(leader.getSet(bytes("k"), bytes("v2")))
                    .isEqualTo(bytes("v"));
            awaitReplicated(cluster, "k", bytes("v2"));
            awaitTrue("ttl cleared", () -> allTtl(cluster, "k", -1), 5000);
        } finally {
            cluster.close();
        }
    }

    @Test
    void getAndSetPreservingTtlKeepsTtl() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            leader.put(bytes("k"), bytes("v"), 100_000);
            awaitReplicated(cluster, "k", bytes("v"));
            assertThat(leader.getAndSetPreservingTtl(bytes("k"), bytes("v2")))
                    .isEqualTo(bytes("v"));
            awaitReplicated(cluster, "k", bytes("v2"));
            awaitTrue("ttl kept", () -> allTtlPositive(cluster, "k"), 5000);
        } finally {
            cluster.close();
        }
    }

    @Test
    void getDeleteReturnsOldAndRemoves() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            leader.put(bytes("k"), bytes("v"));
            awaitReplicated(cluster, "k", bytes("v"));
            assertThat(leader.getDelete(bytes("k"))).isEqualTo(bytes("v"));
            awaitTrue("deleted", () -> allMissing(cluster, "k"), 5000);
        } finally {
            cluster.close();
        }
    }

    @Test
    void putIfAbsentReplicates() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            assertThat(leader.putIfAbsent(bytes("k"), bytes("v1"))).isTrue();
            awaitReplicated(cluster, "k", bytes("v1"));
            assertThat(leader.putIfAbsent(bytes("k"), bytes("v2"))).isFalse();
            awaitTrue("unchanged", () -> allEqual(cluster, "k", bytes("v1")), 5000);
        } finally {
            cluster.close();
        }
    }

    @Test
    void persistAndExpireReplicate() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            leader.put(bytes("k"), bytes("v"), 100_000);
            awaitReplicated(cluster, "k", bytes("v"));
            assertThat(leader.expireAt(bytes("k"),
                    System.currentTimeMillis() + 200_000)).isTrue();
            awaitTrue("expired ttl", () -> allTtlPositive(cluster, "k"), 5000);
            assertThat(leader.persist(bytes("k"))).isTrue();
            awaitTrue("persisted", () -> allTtl(cluster, "k", -1), 5000);
        } finally {
            cluster.close();
        }
    }

    @Test
    void ttlMillisReadsLocalReplicatedTtl() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            leader.put(bytes("k"), bytes("v"), 100_000);
            awaitReplicated(cluster, "k", bytes("v"));
            for (ReplicatedStorageEngine engine : cluster.engines()) {
                long ttl = engine.ttlMillis(bytes("k"));
                assertThat(ttl).as("node ttl").isBetween(90_000L, 100_000L);
            }
        } finally {
            cluster.close();
        }
    }

    @Test
    void updatePreservesTtlAndReplicates() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            leader.put(bytes("k"), bytes("ab"), 100_000);
            awaitReplicated(cluster, "k", bytes("ab"));
            byte[] updated = leader.update(bytes("k"),
                    current -> concat(current == null ? new byte[0] : current,
                            bytes("!")));
            assertThat(updated).isEqualTo(bytes("ab!"));
            awaitReplicated(cluster, "k", bytes("ab!"));
            awaitTrue("ttl kept", () -> allTtlPositive(cluster, "k"), 5000);
        } finally {
            cluster.close();
        }
    }

    @Test
    void leaderFailoverContinuesAtomicOps() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine firstLeader = cluster.leader();
            firstLeader.put(bytes("k"), bytes("10"));
            awaitReplicated(cluster, "k", bytes("10"));
            firstLeader.raft().suspend();
            firstLeader.raft().close();
            List<RaftNode> survivorRafts = cluster.engines().stream()
                    .filter(e -> e.raft() != firstLeader.raft())
                    .map(ReplicatedStorageEngine::raft)
                    .toList();
            RaftNode newLeader = awaitLeader(survivorRafts, 5000);
            ReplicatedStorageEngine leader = cluster.engines().stream()
                    .filter(e -> e.raft() == newLeader)
                    .findFirst()
                    .orElseThrow();
            assertThat(leader.increment(bytes("k"), 5)).isEqualTo(15L);
            List<ReplicatedStorageEngine> survivors = cluster.engines().stream()
                    .filter(e -> e.raft() != firstLeader.raft())
                    .toList();
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                boolean replicated = survivors.stream()
                        .allMatch(e -> bytes("15").equals(e.get(bytes("k"))));
                if (replicated) {
                    break;
                }
                Thread.sleep(50);
            }
            List<String> states = survivors.stream()
                    .map(e -> e.raft().id() + "=" + e.raft().state()
                            + ":v=" + new String(e.get(bytes("k")),
                            StandardCharsets.UTF_8)
                            + ":commit=" + e.raft().commitIndex())
                    .toList();
            assertThat(survivors).as("failover replicated " + states)
                    .allSatisfy(e -> assertThat(e.get(bytes("k")))
                            .isEqualTo(bytes("15")));
        } finally {
            cluster.close();
        }
    }

    @Test
    void unsupportedLocalFailsExplicitly() throws Exception {
        ReplicatedStorageEngine engine = ReplicatedStorageEngine.create(
                "n1", List.of(), new PlainStorage(),
                ELECTION, 25, 10);
        try {
            engine.raft().start();
            awaitLeader(List.of(engine.raft()), 5000);
            assertThatThrownBy(() -> engine.increment(bytes("k"), 1))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> engine.ttlMillis(bytes("k")))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> engine.persist(bytes("k")))
                    .isInstanceOf(UnsupportedOperationException.class);
        } finally {
            engine.raft().close();
        }
    }

    @Test
    void atomicOpsReplayAfterRestart() throws Exception {
        Path logDir = dir.resolve("raftlog");
        Path stateDir = dir.resolve("state");
        ReplicatedStorageEngine first = ReplicatedStorageEngine.create(
                "n1", new LocalRaftTransport(new ArrayList<>(), "n1"),
                MemTable.create(), ELECTION, 25, 10,
                FileRaftLog.open(logDir, Durability.SYNC),
                RaftPersistentState.open(stateDir), null);
        first.raft().start();
        awaitLeader(List.of(first.raft()), 5000);
        first.put(bytes("n"), bytes("5"));
        assertThat(first.increment(bytes("n"), 2)).isEqualTo(7L);
        first.put(bytes("s"), bytes("ab"));
        assertThat(first.append(bytes("s"), bytes("cd"))).isEqualTo(4);
        first.raft().close();

        ReplicatedStorageEngine restarted = ReplicatedStorageEngine.create(
                "n1", new LocalRaftTransport(new ArrayList<>(), "n1"),
                MemTable.create(), ELECTION, 25, 10,
                FileRaftLog.open(logDir, Durability.SYNC),
                RaftPersistentState.open(stateDir), null);
        restarted.raft().start();
        awaitLeader(List.of(restarted.raft()), 5000);
        assertThat(restarted.get(bytes("n"))).isEqualTo(bytes("7"));
        assertThat(restarted.get(bytes("s"))).isEqualTo(bytes("abcd"));
        assertThat(restarted.increment(bytes("n"), 3)).isEqualTo(10L);
        assertThat(restarted.get(bytes("n"))).isEqualTo(bytes("10"));
        assertThat(restarted.get(bytes("s"))).isEqualTo(bytes("abcd"));
        restarted.raft().close();
    }

    @Test
    void domainErrorReturnsWithoutHangingAndNodeStaysHealthy()
            throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            leader.put(bytes("k"), bytes("v"));
            awaitReplicated(cluster, "k", bytes("v"));
            assertThatThrownBy(() -> leader.increment(bytes("k"), 1))
                    .isInstanceOf(NumberFormatException.class);
            leader.put(bytes("k"), bytes("5"));
            awaitReplicated(cluster, "k", bytes("5"));
            assertThat(leader.increment(bytes("k"), 1)).isEqualTo(6L);
            awaitReplicated(cluster, "k", bytes("6"));
        } finally {
            cluster.close();
        }
    }

    @Test
    void gatewayStringCommandsUseAtomicPath() throws Exception {
        Cluster cluster = cluster();
        try {
            ReplicatedStorageEngine leader = cluster.leader();
            Map<Integer, String> shardLeaders = Map.of(0, leader.raft().id());
            Map<String, StorageEngine> storages = new HashMap<>();
            Map<String, InetSocketAddress> addresses = new HashMap<>();
            int port = 7100;
            for (ReplicatedStorageEngine engine : cluster.engines()) {
                storages.put(engine.raft().id(), engine);
                addresses.put(engine.raft().id(),
                        new InetSocketAddress("127.0.0.1", port++));
            }
            RedisClusterGateway gateway = new RedisClusterGateway(
                    1, shardLeaders, storages, addresses, leader.raft().id());

            assertThat(gateway.execute("setex", List.of(
                    bytes("k"), bytes("100"), bytes("5"))))
                    .isInstanceOf(RespSimpleString.class);
            RespValue ttl = gateway.execute("ttl", List.of(bytes("k")));
            assertThat(ttl).isInstanceOf(RespInteger.class);
            assertThat(((RespInteger) ttl).value()).isBetween(99L, 100L);

            RespValue incr = gateway.execute("incr", List.of(bytes("k")));
            assertThat(incr).isInstanceOf(RespInteger.class);
            assertThat(((RespInteger) incr).value()).isEqualTo(6L);

            assertThat(gateway.execute("set", List.of(
                    bytes("bad"), bytes("v"))))
                    .isInstanceOf(RespSimpleString.class);
            assertThat(gateway.execute("incr", List.of(bytes("bad"))))
                    .isInstanceOf(io.tieringkv.protocol.RespError.class);
            assertThat(gateway.execute("set", List.of(
                    bytes("bad"), bytes("1"))))
                    .isInstanceOf(RespSimpleString.class);
            assertThat(((RespInteger) gateway.execute(
                    "incr", List.of(bytes("bad")))).value()).isEqualTo(2L);

            assertThat(gateway.execute("getset", List.of(
                    bytes("k"), bytes("v2")))).isInstanceOf(RespBulkString.class);
            RespValue ttlAfterGetSet = gateway.execute(
                    "ttl", List.of(bytes("k")));
            assertThat(((RespInteger) ttlAfterGetSet).value()).isEqualTo(-1L);

            assertThat(gateway.execute("setnx", List.of(
                    bytes("nx"), bytes("a")))).isInstanceOf(RespInteger.class);
            assertThat(((RespInteger) gateway.execute("setnx", List.of(
                    bytes("nx"), bytes("b")))).value()).isZero();

            assertThat(gateway.execute("getdel", List.of(bytes("nx"))))
                    .isInstanceOf(RespBulkString.class);
            assertThat(gateway.execute("get", List.of(bytes("nx"))))
                    .isEqualTo(RespNull.BULK_STRING);
        } finally {
            cluster.close();
        }
    }

    // ---------- fixture helpers ----------

    private static Cluster cluster() throws Exception {
        List<RaftNode> peers = new ArrayList<>();
        List<ReplicatedStorageEngine> engines = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            engines.add(ReplicatedStorageEngine.create(
                    id, peers, MemTable.create(), ELECTION, 25, 10));
        }
        peers.addAll(engines.stream().map(ReplicatedStorageEngine::raft).toList());
        for (ReplicatedStorageEngine engine : engines) {
            engine.raft().start();
        }
        awaitLeader(peers, 5000);
        return new Cluster(engines);
    }

    private static void awaitReplicated(Cluster cluster, String key,
                                        byte[] expected) throws Exception {
        awaitTrue("replicated " + key,
                () -> allEqual(cluster, key, expected), 5000);
    }

    private static boolean allEqual(Cluster cluster, String key,
                                    byte[] expected) {
        return cluster.engines().stream()
                .allMatch(e -> java.util.Arrays.equals(expected, e.get(bytes(key))));
    }

    private static boolean allMissing(Cluster cluster, String key) {
        return cluster.engines().stream()
                .allMatch(e -> e.get(bytes(key)) == null);
    }

    private static boolean allTtl(Cluster cluster, String key, long expected) {
        return cluster.engines().stream()
                .allMatch(e -> e.ttlMillis(bytes(key)) == expected);
    }

    private static boolean allTtlPositive(Cluster cluster, String key) {
        return cluster.engines().stream()
                .allMatch(e -> e.ttlMillis(bytes(key)) > 0);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] merged = new byte[a.length + b.length];
        System.arraycopy(a, 0, merged, 0, a.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        return merged;
    }

    private record Cluster(List<ReplicatedStorageEngine> engines)
            implements AutoCloseable {

        ReplicatedStorageEngine leader() throws Exception {
            List<RaftNode> rafts = engines.stream()
                    .map(ReplicatedStorageEngine::raft)
                    .toList();
            RaftNode leader = awaitLeader(rafts, 5000);
            assertThat(leader.state()).isEqualTo(RaftState.LEADER);
            return engines.stream()
                    .filter(e -> e.raft() == leader)
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() {
            for (ReplicatedStorageEngine engine : engines) {
                engine.raft().close();
            }
        }
    }

    /** 不支持 AtomicStringOps 的底层存储（验证显式失败而非静默回退）。 */
    private static final class PlainStorage implements StorageEngine {
        @Override
        public void put(byte[] key, byte[] value) {
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
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
        public StorageIterator iterator() {
            return new StorageIterator() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public KeyValueEntry next() {
                    throw new NoSuchElementException();
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
    }
}
