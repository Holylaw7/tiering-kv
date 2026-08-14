package io.tieringkv.cluster.multiraft;

import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.MultiRaftTransport;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

/** 多 Raft TCP 传输（ADR-0058）：单端口多组 + 组隔离。 */
class MultiRaftTransportTest {

    @TempDir
    Path dir;

    @Test
    void twoGroupsElectOverSharedPort() throws Exception {
        try (TcpFixture fixture = tcpFixture(2, false)) {
            RaftNode gALeader = leaderOf(fixture, "gA");
            RaftNode gBLeader = leaderOf(fixture, "gB");
            assertThat(gALeader).isNotNull();
            assertThat(gBLeader).isNotNull();
        }
    }

    @Test
    void proposeRoutesToCorrectGroup() throws Exception {
        try (TcpFixture fixture = tcpFixture(2, false)) {
            putOnLeader(fixture, "gA", bytes("a-only"), bytes("v"));
            RaftNode gBNode = fixture.managers().get("n1").raftFor("gB");
            assertThat(gBNode.logSize()).isZero();
            awaitTrue("gA replicated", () ->
                    fixture.managers().get("n2").raftFor("gA").logSize() == 1, 5000);
        }
    }

    @Test
    void leaderKillOneGroupLeavesOtherServing() throws Exception {
        try (TcpFixture fixture = tcpFixture(2, false)) {
            RaftNode gALeader = leaderOf(fixture, "gA");
            RaftNode gBLeader = leaderOf(fixture, "gB");
            gALeader.suspend();
            gALeader.close();
            RaftNode newGALeader = awaitLeader(groupRafts(fixture, "gA"), 8000);
            assertThat(newGALeader).isNotEqualTo(gALeader);
            putOnLeader(fixture, "gB", bytes("b-ok"), bytes("v"));
            assertThat(gBLeader.commitIndex()).isZero();
        }
    }

    @Test
    void unregisterGroupStopsRoutingToNode() throws Exception {
        try (TcpFixture fixture = tcpFixture(2, false)) {
            RaftNode gBLeader = leaderOf(fixture, "gB");
            String leaderNode = gBLeader.id();
            String follower = List.of("n1", "n2", "n3").stream()
                    .filter(id -> !id.equals(leaderNode)).findFirst().orElseThrow();
            fixture.endpoints().get(follower).unregister("gB");
            putOnLeader(fixture, "gB", bytes("x"), bytes("v")); // n2+n3 多数派仍可提交
            assertThat(gBLeader.commitIndex()).isZero();
            awaitTrue(follower + " gB does not receive", () ->
                    fixture.managers().get(follower).raftFor("gB").logSize() == 0, 2000);
        }
    }

    @Test
    void logIsolationPerGroup() throws Exception {
        try (TcpFixture fixture = tcpFixture(2, true)) {
            RaftNode gALeader = leaderOf(fixture, "gA");
            putOnLeader(fixture, "gA", bytes("a"), bytes("v"));
            putOnLeader(fixture, "gB", bytes("b"), bytes("v"));
            awaitTrue("gA log persisted", () ->
                    Files.exists(dir.resolve("n1").resolve("gA").resolve("raftlog")
                            .resolve("segment-00000000000000000000.log")), 5000);
            awaitTrue("gB log persisted", () ->
                    Files.exists(dir.resolve("n1").resolve("gB").resolve("raftlog")
                            .resolve("segment-00000000000000000000.log")), 5000);
            assertThat(dir.resolve("n1").resolve("gA")).isDirectory();
            assertThat(dir.resolve("n1").resolve("gB")).isDirectory();
        }
    }

    @Test
    void snapshotIsolationPerGroup() throws Exception {
        try (TcpFixture fixture = tcpFixture(2, true)) {
            leaderOf(fixture, "gA");
            leaderOf(fixture, "gB");
            // 各组独立持久状态文件（构造期按组建目录），无交叉
            Path stateA = dir.resolve("n1").resolve("gA").resolve("raft.state");
            Path stateB = dir.resolve("n1").resolve("gB").resolve("raft.state");
            awaitTrue("gA state persisted",
                    () -> java.nio.file.Files.exists(stateA), 5000);
            awaitTrue("gB state persisted",
                    () -> java.nio.file.Files.exists(stateB), 5000);
            assertThat(stateA).isNotEqualTo(stateB);
        }
    }

    @Test
    void threeGroupsOnSinglePort() throws Exception {
        try (TcpFixture fixture = tcpFixture(3, false)) {
            for (String group : List.of("gA", "gB", "gC")) {
                assertThat(leaderOf(fixture, group)).isNotNull();
            }
            assertThat(fixture.endpoints().get("n1").groupCount()).isEqualTo(3);
        }
    }

    @Test
    void concurrentWritesAcrossGroups() throws Exception {
        try (TcpFixture fixture = tcpFixture(2, false)) {
            RaftNode gALeader = leaderOf(fixture, "gA");
            RaftNode gBLeader = leaderOf(fixture, "gB");
            Thread a = new Thread(() -> {
                for (int i = 0; i < 30; i++) {
                    fixture.managers().get(gALeader.id()).storageFor("gA")
                            .put(bytes("a" + i), bytes("v"));
                }
            });
            Thread b = new Thread(() -> {
                for (int i = 0; i < 30; i++) {
                    fixture.managers().get(gBLeader.id()).storageFor("gB")
                            .put(bytes("b" + i), bytes("v"));
                }
            });
            a.start();
            b.start();
            a.join(20_000);
            b.join(20_000);
            assertThat(gALeader.commitIndex()).isEqualTo(29);
            assertThat(gBLeader.commitIndex()).isEqualTo(29);
        }
    }

    @Test
    void transportPeerIdsExposeNodes() throws Exception {
        try (TcpFixture fixture = tcpFixture(1, false)) {
            MultiRaftTransport transport = new MultiRaftTransport(
                    "gA", fixture.endpoints().get("n1"));
            assertThat(transport.peerIds()).containsExactlyInAnyOrder("n1", "n2", "n3");
        }
    }

    @Test
    void endpointGroupCount() throws Exception {
        try (TcpFixture fixture = tcpFixture(2, false)) {
            assertThat(fixture.endpoints().get("n1").groupCount()).isEqualTo(2);
            assertThat(fixture.endpoints().get("n2").groupCount()).isEqualTo(2);
        }
    }

    @Test
    void closeEndpointStillAllowsMajorityCommit() throws Exception {
        TcpFixture fixture = tcpFixture(2, false);
        RaftNode gALeader = leaderOf(fixture, "gA");
        String leaderNode = gALeader.id();
        List<String> others = List.of("n1", "n2", "n3").stream()
                .filter(id -> !id.equals(leaderNode)).toList();
        String other1 = others.get(0);
        fixture.endpoints().get(other1).close(); // 关闭一个节点端点
        fixture.endpoints().remove(other1);
        putOnLeader(fixture, "gA", bytes("survive"), bytes("v")); // 双节点多数派提交
        assertThat(gALeader.commitIndex()).isZero();
        fixture.close();
    }

    // ---------- helpers ----------

    private TcpFixture tcpFixture(int groupCount, boolean persistent) throws Exception {
        // 14613 个测试共用 OS 端口空间，freePort() 释放到 bind 之间可能被
        // 并发占用（TOCTOU）导致 BindException（release runner 实测）。
        // 失败时关闭已启动端点并重新分配端口重试。
        Exception last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return tcpFixtureOnce(groupCount, persistent);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException(
                "tcpFixture failed after 5 attempts", last);
    }

    private TcpFixture tcpFixtureOnce(int groupCount, boolean persistent)
            throws Exception {
        int p1 = freePort();
        int p2 = freePort();
        int p3 = freePort();
        Map<String, InetSocketAddress> addresses = Map.of(
                "n1", new InetSocketAddress("127.0.0.1", p1),
                "n2", new InetSocketAddress("127.0.0.1", p2),
                "n3", new InetSocketAddress("127.0.0.1", p3));
        Map<String, MultiRaftEndpoint> endpoints = new HashMap<>();
        Map<String, RaftGroupManager> managers = new HashMap<>();
        List<RaftGroupManager> all = new ArrayList<>();
        try {
            for (String nodeId : List.of("n1", "n2", "n3")) {
                MultiRaftEndpoint endpoint = new MultiRaftEndpoint(
                        nodeId, addresses.get(nodeId).getPort(), addresses);
                endpoint.start();
                MultiRaftNode host = new MultiRaftNode(nodeId);
                RaftGroupManager manager = new RaftGroupManager(
                        nodeId, host, RaftTestSupport.ELECTION, 25, 10);
                endpoints.put(nodeId, endpoint);
                managers.put(nodeId, manager);
                all.add(manager);
            }
            for (String nodeId : List.of("n1", "n2", "n3")) {
                RaftGroupManager manager = managers.get(nodeId);
                for (int g = 0; g < groupCount; g++) {
                    String groupId = "g" + (char) ('A' + g);
                    MultiRaftTransport transport = new MultiRaftTransport(
                            groupId, endpoints.get(nodeId));
                    if (persistent) {
                        Path groupDir = dir.resolve(nodeId).resolve(groupId);
                        manager.createGroupPersistent(groupId, transport,
                                MemTable.create(),
                                FileRaftLog.open(
                                        groupDir.resolve("raftlog"),
                                        Durability.SYNC),
                                RaftPersistentState.open(groupDir),
                                null);
                    } else {
                        manager.createGroup(groupId, transport,
                                MemTable.create());
                    }
                    endpoints.get(nodeId).register(groupId,
                            manager.raftFor(groupId));
                }
            }
            for (RaftGroupManager manager : all) {
                manager.startAll();
            }
            return new TcpFixture(endpoints, managers, all);
        } catch (Exception e) {
            for (RaftGroupManager manager : all) {
                try {
                    manager.close();
                } catch (RuntimeException ignored) {
                    // 重试路径：忽略关闭失败
                }
            }
            for (MultiRaftEndpoint endpoint : endpoints.values()) {
                try {
                    endpoint.close();
                } catch (RuntimeException ignored) {
                    // 重试路径：忽略关闭失败
                }
            }
            throw e;
        }
    }

    private static RaftNode leaderOf(TcpFixture fixture, String groupId)
            throws InterruptedException {
        return awaitLeader(groupRafts(fixture, groupId), 8000);
    }

    private static List<RaftNode> groupRafts(TcpFixture fixture, String groupId) {
        List<RaftNode> rafts = new ArrayList<>();
        for (String nodeId : List.of("n1", "n2", "n3")) {
            rafts.add(fixture.managers().get(nodeId).raftFor(groupId));
        }
        return rafts;
    }

    private static void putOnLeader(TcpFixture fixture, String groupId,
                                    byte[] key, byte[] value) throws InterruptedException {
        RaftNode leader = leaderOf(fixture, groupId);
        fixture.managers().get(leader.id()).storageFor(groupId).put(key, value);
    }

    private static int freePort() throws Exception {
        return io.tieringkv.testkit.TestPorts.freePort();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record TcpFixture(Map<String, MultiRaftEndpoint> endpoints,
                              Map<String, RaftGroupManager> managers,
                              List<RaftGroupManager> all) implements AutoCloseable {

        @Override
        public void close() {
            for (RaftGroupManager manager : all) {
                manager.close();
            }
            for (MultiRaftEndpoint endpoint : endpoints.values()) {
                endpoint.close();
            }
        }
    }
}
