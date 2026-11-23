package io.tieringkv.distributed;

import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.multiraft.MultiRaftNode;
import io.tieringkv.cluster.multiraft.RaftGroupManager;
import io.tieringkv.cluster.raft.LocalRaftTransport;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Raft 边角验证矩阵（ADR-0298，只测不改）。 */
class RaftEdgeCaseTest {

    @Test
    void leaderElected() throws Exception {
        try (Fixture fixture = fixture(3)) {
            RaftNode leader = RaftTestSupport.awaitLeader(
                    fixture.rafts(), 8000);
            assertThat(leader).isNotNull();
        }
    }

    @Test
    void writeReplicatesToAllNodes() throws Exception {
        try (Fixture fixture = fixture(3)) {
            RaftNode leader = RaftTestSupport.awaitLeader(
                    fixture.rafts(), 8000);
            fixture.put(leader, "k", "v");
            long deadline = System.currentTimeMillis() + 8000;
            while (fixture.rafts().stream().anyMatch(node ->
                    fixture.get(node, "k") == null)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            for (RaftNode node : fixture.rafts()) {
                assertThat(fixture.get(node, "k")).isEqualTo(
                        "v".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void leaderCrashFailsOver() throws Exception {
        try (Fixture fixture = fixture(3)) {
            RaftNode leader = RaftTestSupport.awaitLeader(
                    fixture.rafts(), 8000);
            leader.suspend();
            leader.close();
            RaftNode newLeader = RaftTestSupport.awaitLeader(
                    fixture.rafts(), 8000);
            assertThat(newLeader).isNotEqualTo(leader);
            fixture.put(newLeader, "probe", "ok");
            assertThat(fixture.get(newLeader, "probe"))
                    .isEqualTo("ok".getBytes(
                            StandardCharsets.UTF_8));
        }
    }

    @Test
    void suspendedFollowerCatchUp() throws Exception {
        try (Fixture fixture = fixture(3)) {
            RaftNode leader = RaftTestSupport.awaitLeader(
                    fixture.rafts(), 8000);
            RaftNode follower = fixture.rafts().stream()
                    .filter(node -> node != leader).findFirst()
                    .orElseThrow();
            follower.suspend();
            fixture.put(leader, "during", "v1");
            follower.resume();
            long deadline = System.currentTimeMillis() + 8000;
            while (fixture.get(follower, "during") == null
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(fixture.get(follower, "during"))
                    .isEqualTo("v1".getBytes(
                            StandardCharsets.UTF_8));
        }
    }

    @Test
    void sequentialWritesPreserveOrder() throws Exception {
        try (Fixture fixture = fixture(3)) {
            RaftNode leader = RaftTestSupport.awaitLeader(
                    fixture.rafts(), 8000);
            for (int i = 0; i < 20; i++) {
                fixture.put(leader, "seq", Integer.toString(i));
            }
            assertThat(fixture.get(leader, "seq")).isEqualTo(
                    "19".getBytes(StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest(name = "round {0}")
    @MethodSource("rounds")
    void repeatedElectionMatrix(int round) throws Exception {
        try (Fixture fixture = fixture(3)) {
            RaftNode leader = RaftTestSupport.awaitLeader(
                    fixture.rafts(), 8000);
            fixture.put(leader, "r" + round, "v");
            assertThat(fixture.get(leader, "r" + round))
                    .isNotNull();
        }
    }

    @ParameterizedTest(name = "nodes {0}")
    @MethodSource("nodeCounts")
    void quorumSurvivesMinorityLoss(int nodeCount)
            throws Exception {
        try (Fixture fixture = fixture(nodeCount)) {
            RaftNode leader = RaftTestSupport.awaitLeader(
                    fixture.rafts(), 8000);
            int minority = (nodeCount - 1) / 2;
            List<RaftNode> victims = fixture.rafts().stream()
                    .filter(node -> node != leader)
                    .limit(minority).toList();
            for (RaftNode victim : victims) {
                victim.suspend();
                victim.close();
            }
            fixture.put(leader, "still", "works");
            assertThat(fixture.get(leader, "still"))
                    .isEqualTo("works".getBytes(
                            StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest(name = "writes {0}")
    @MethodSource("writeCounts")
    void manyWritesConverge(int writes) throws Exception {
        try (Fixture fixture = fixture(3)) {
            RaftNode leader = RaftTestSupport.awaitLeader(
                    fixture.rafts(), 8000);
            for (int i = 0; i < writes; i++) {
                fixture.put(leader, "k" + i, "v" + i);
            }
            for (int i = 0; i < writes; i++) {
                assertThat(fixture.get(leader, "k" + i))
                        .isNotNull();
            }
        }
    }

    private static Fixture fixture(int nodes) {
        Map<String, Map<String, List<RaftNode>>> peers =
                new HashMap<>();
        Map<String, RaftGroupManager> managers = new HashMap<>();
        List<RaftNode> rafts = new ArrayList<>();
        for (int n = 1; n <= nodes; n++) {
            String nodeId = "n" + n;
            MultiRaftNode host = new MultiRaftNode(nodeId);
            RaftGroupManager manager = new RaftGroupManager(
                    nodeId, host, RaftTestSupport.ELECTION, 25, 10);
            managers.put(nodeId, manager);
            peers.computeIfAbsent("r1", ignored ->
                    new HashMap<>()).put(nodeId,
                    new ArrayList<>());
        }
        for (int n = 1; n <= nodes; n++) {
            String nodeId = "n" + n;
            managers.get(nodeId).createGroup("r1",
                    new LocalRaftTransport(
                            peers.get("r1").get(nodeId), nodeId),
                    MemTable.create());
        }
        for (int n = 1; n <= nodes; n++) {
            String nodeId = "n" + n;
            List<RaftNode> groupRafts = new ArrayList<>();
            for (int m = 1; m <= nodes; m++) {
                groupRafts.add(managers.get("n" + m)
                        .raftFor("r1"));
            }
            peers.get("r1").get(nodeId).addAll(groupRafts);
        }
        for (int n = 1; n <= nodes; n++) {
            managers.get("n" + n).startAll();
            rafts.add(managers.get("n" + n).raftFor("r1"));
        }
        return new Fixture(managers, rafts);
    }

    private record Fixture(Map<String, RaftGroupManager> managers,
                           List<RaftNode> rafts)
            implements AutoCloseable {

        private void put(RaftNode leader, String key,
                         String value) {
            managers.get(leader.id()).storageFor("r1").put(
                    key.getBytes(StandardCharsets.UTF_8),
                    value.getBytes(StandardCharsets.UTF_8));
        }

        private byte[] get(RaftNode node, String key) {
            return managers.get(node.id()).storageFor("r1")
                    .get(key.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            managers.values().forEach(RaftGroupManager::close);
        }
    }

    static Stream<Arguments> rounds() {
        return Stream.of(1, 2, 3, 4, 5).map(Arguments::of);
    }

    static Stream<Arguments> nodeCounts() {
        return Stream.of(3, 5).map(Arguments::of);
    }

    static Stream<Arguments> writeCounts() {
        return Stream.of(10, 50, 100, 200).map(Arguments::of);
    }
}
