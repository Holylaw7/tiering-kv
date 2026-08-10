package io.tieringkv.cluster.multiraft;

import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.ReplicatedStorageEngine;
import io.tieringkv.cluster.raft.LocalRaftTransport;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Raft 组管理器（ADR-0058）：按 Region 创建独立组与存储隔离。 */
class RaftGroupManagerTest {

    @Test
    void createGroupRegistersRaft() {
        Fixture fixture = fixture(1);
        try {
            assertThat(fixture.managers().get("n1").groupCount()).isEqualTo(1);
            assertThat(fixture.managers().get("n1").raftFor("gA")).isNotNull();
        } finally {
            fixture.close();
        }
    }

    @Test
    void twoGroupsElectLeadersIndependently() throws Exception {
        Fixture fixture = fixture(2);
        try {
            RaftNode gALeader = awaitLeader(List.of(
                    fixture.managers().get("n1").raftFor("gA"),
                    fixture.managers().get("n2").raftFor("gA"),
                    fixture.managers().get("n3").raftFor("gA")), 5000);
            RaftNode gBLeader = awaitLeader(List.of(
                    fixture.managers().get("n1").raftFor("gB"),
                    fixture.managers().get("n2").raftFor("gB"),
                    fixture.managers().get("n3").raftFor("gB")), 5000);
            assertThat(gALeader.state()).isEqualTo(RaftState.LEADER);
            assertThat(gBLeader.state()).isEqualTo(RaftState.LEADER);
        } finally {
            fixture.close();
        }
    }

    @Test
    void proposeInOneGroupDoesNotAffectOther() throws Exception {
        Fixture fixture = fixture(2);
        try {
            putOnLeader(fixture, "gA", bytes("only-a"), bytes("v"));
            RaftNode gBAny = fixture.managers().get("n1").raftFor("gB");
            assertThat(gBAny.logSize()).isZero();
            RaftNode gALeader = awaitLeader(groupRafts(fixture, "gA"), 5000);
            assertThat(gALeader.logSize()).isEqualTo(1);
        } finally {
            fixture.close();
        }
    }

    @Test
    void destroyGroupStopsOnlyThatGroup() throws Exception {
        Fixture fixture = fixture(2);
        try {
            awaitLeader(groupRafts(fixture, "gA"), 5000);
            fixture.managers().get("n1").destroy("gA");
            putOnLeader(fixture, "gB", bytes("b"), bytes("v"));
            RaftNode gBLeader = awaitLeader(groupRafts(fixture, "gB"), 5000);
            assertThat(gBLeader.commitIndex()).isZero();
            assertThat(fixture.managers().get("n1").groupCount()).isEqualTo(1);
        } finally {
            fixture.close();
        }
    }

    @Test
    void storageIsolationBetweenGroups() throws Exception {
        Fixture fixture = fixture(2);
        try {
            putOnLeader(fixture, "gA", bytes("key-a"), bytes("va"));
            putOnLeader(fixture, "gB", bytes("key-b"), bytes("vb"));
            assertThat(fixture.managers().get("n1").storageFor("gA")
                    .get(bytes("key-a"))).isNotNull();
            assertThat(fixture.managers().get("n1").storageFor("gA")
                    .get(bytes("key-b"))).isNull();
            assertThat(fixture.managers().get("n1").storageFor("gB")
                    .get(bytes("key-b"))).isNotNull();
        } finally {
            fixture.close();
        }
    }

    @Test
    void storageForUnknownThrows() {
        Fixture fixture = fixture(1);
        try {
            assertThatThrownBy(() -> fixture.managers().get("n1").storageFor("missing"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void raftForUnknownThrows() {
        Fixture fixture = fixture(1);
        try {
            assertThatThrownBy(() -> fixture.managers().get("n1").raftFor("missing"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void leaderCrashInOneGroupLeavesOtherIntact() throws Exception {
        Fixture fixture = fixture(2);
        try {
            RaftNode gALeader = awaitLeader(groupRafts(fixture, "gA"), 5000);
            RaftNode gBLeader = awaitLeader(groupRafts(fixture, "gB"), 5000);
            gALeader.suspend();
            gALeader.close();
            RaftNode newGALeader = awaitLeader(groupRafts(fixture, "gA"), 5000);
            assertThat(newGALeader).isNotEqualTo(gALeader);
            putOnLeader(fixture, "gB", bytes("b-ok"), bytes("v"));
            assertThat(gBLeader.commitIndex()).isZero();
        } finally {
            fixture.close();
        }
    }

    @Test
    void concurrentProposalsAcrossGroups() throws Exception {
        Fixture fixture = fixture(2);
        try {
            RaftNode gALeader = awaitLeader(groupRafts(fixture, "gA"), 5000);
            RaftNode gBLeader = awaitLeader(groupRafts(fixture, "gB"), 5000);
            Thread a = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    fixture.managers().get(gALeader.id()).storageFor("gA")
                            .put(bytes("a" + i), bytes("v"));
                }
            });
            Thread b = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    fixture.managers().get(gBLeader.id()).storageFor("gB")
                            .put(bytes("b" + i), bytes("v"));
                }
            });
            a.start();
            b.start();
            a.join(10_000);
            b.join(10_000);
            assertThat(gALeader.commitIndex()).isEqualTo(49);
            assertThat(gBLeader.commitIndex()).isEqualTo(49);
        } finally {
            fixture.close();
        }
    }

    @Test
    void closeClosesAllGroups() {
        Fixture fixture = fixture(2);
        fixture.close();
        assertThat(fixture.managers().get("n1").groupCount()).isZero();
    }

    @Test
    void createGroupDuplicateRejected() {
        Fixture fixture = fixture(1);
        try {
            RaftGroupManager manager = fixture.managers().get("n1");
            assertThatThrownBy(() -> manager.createGroup("gA",
                    new LocalRaftTransport(new ArrayList<>(), "n1"), MemTable.create()))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            fixture.close();
        }
    }

    private static List<RaftNode> groupRafts(Fixture fixture, String groupId) {
        List<RaftNode> rafts = new ArrayList<>();
        for (String nodeId : List.of("n1", "n2", "n3")) {
            rafts.add(fixture.managers().get(nodeId).raftFor(groupId));
        }
        return rafts;
    }

    private static void putOnLeader(Fixture fixture, String groupId,
                                    byte[] key, byte[] value) throws InterruptedException {
        RaftNode leader = awaitLeader(groupRafts(fixture, groupId), 5000);
        fixture.managers().get(leader.id()).storageFor(groupId).put(key, value);
    }

    /** 3 节点 × groupCount 个组的进程内集群（LocalRaftTransport 按组分隔）。 */
    private static Fixture fixture(int groupCount) {
        Map<String, Map<String, List<RaftNode>>> peers = new HashMap<>();
        Map<String, RaftGroupManager> managers = new HashMap<>();
        List<RaftGroupManager> all = new ArrayList<>();
        for (String nodeId : List.of("n1", "n2", "n3")) {
            MultiRaftNode host = new MultiRaftNode(nodeId);
            RaftGroupManager manager = new RaftGroupManager(
                    nodeId, host, RaftTestSupport.ELECTION, 25, 10);
            managers.put(nodeId, manager);
            all.add(manager);
            for (int g = 0; g < groupCount; g++) {
                String groupId = "g" + (char) ('A' + g);
                peers.computeIfAbsent(groupId, ignored -> new HashMap<>())
                        .put(nodeId, new ArrayList<>());
            }
        }
        for (String nodeId : List.of("n1", "n2", "n3")) {
            RaftGroupManager manager = managers.get(nodeId);
            for (int g = 0; g < groupCount; g++) {
                String groupId = "g" + (char) ('A' + g);
                LocalRaftTransport transport = new LocalRaftTransport(
                        peers.get(groupId).get(nodeId), nodeId);
                manager.createGroup(groupId, transport, MemTable.create());
            }
        }
        for (int g = 0; g < groupCount; g++) {
            String groupId = "g" + (char) ('A' + g);
            List<RaftNode> groupRafts = new ArrayList<>();
            for (String nodeId : List.of("n1", "n2", "n3")) {
                groupRafts.add(managers.get(nodeId).raftFor(groupId));
            }
            peers.get(groupId).values().forEach(list -> list.addAll(groupRafts));
        }
        for (RaftGroupManager manager : all) {
            manager.startAll();
        }
        return new Fixture(managers, all);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(Map<String, RaftGroupManager> managers,
                           List<RaftGroupManager> all) implements AutoCloseable {

        @Override
        public void close() {
            for (RaftGroupManager manager : all) {
                manager.close();
            }
        }
    }
}
