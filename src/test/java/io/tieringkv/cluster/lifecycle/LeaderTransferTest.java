package io.tieringkv.cluster.lifecycle;

import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.multiraft.MultiRaftNode;
import io.tieringkv.cluster.multiraft.RaftGroupManager;
import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.TimeoutNowRequest;
import io.tieringkv.cluster.raft.TimeoutNowResponse;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import io.tieringkv.cluster.raft.client.AsyncProposalQueue;
import io.tieringkv.cluster.raft.client.AsyncReplicationClient;
import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.MultiRaftTransport;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.tieringkv.cluster.RaftTestSupport.ELECTION;
import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 leader 交接（ADR-0064）：TimeoutNow / 日志追平校验 / 无数据丢失。 */
class LeaderTransferTest {

    @Test
    void transferLeadershipRejectsNonLeader() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode leader = fixture.leader();
            RaftNode follower = fixture.followerOf(leader.id());
            assertThat(follower.transferLeadership(leader.id()).get(5, TimeUnit.SECONDS))
                    .isFalse();
        }
    }

    @Test
    void transferLeadershipRejectsSelf() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode leader = fixture.leader();
            assertThat(leader.transferLeadership(leader.id()).get(5, TimeUnit.SECONDS))
                    .isFalse();
        }
    }

    @Test
    void transferLeadershipRejectsUnknownTarget() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode leader = fixture.leader();
            assertThat(leader.transferLeadership("n9").get(5, TimeUnit.SECONDS))
                    .isFalse();
        }
    }

    @Test
    void transferLeadershipRejectsLaggingTarget() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode leader = fixture.leader();
            RaftNode target = fixture.followerOf(leader.id());
            fixture.dropAppendTo(target.id());
            leader.propose(bytes("B")).get(); // target 滞后
            assertThat(leader.transferLeadership(target.id())
                    .get(5, TimeUnit.SECONDS)).isFalse();
            fixture.clearDrop();
            awaitTrue("target catches up", () ->
                    target.logSize() == leader.logSize()
                            && leader.replication().matchIndex(target.id())
                            >= leader.lastLogIndex(), 5000);
            assertThat(leader.transferLeadership(target.id())
                    .get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void transferToCaughtUpFollowerSucceeds() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode leader = fixture.leader();
            RaftNode target = fixture.followerOf(leader.id());
            leader.propose(bytes("A")).get();
            awaitTrue("all caught up", () ->
                    fixture.nodes().stream().allMatch(n -> n.logSize() == 1), 3000);
            assertThat(leader.transferLeadership(target.id())
                    .get(5, TimeUnit.SECONDS)).isTrue();
            awaitTrue("target becomes leader", () ->
                    target.state() == RaftState.LEADER, 5000);
            awaitTrue("old leader steps down", () ->
                    leader.state() == RaftState.FOLLOWER, 5000);
        }
    }

    @Test
    void termAdvancesAfterTransfer() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode leader = fixture.leader();
            RaftNode target = fixture.followerOf(leader.id());
            long oldTerm = leader.currentTerm();
            leader.transferLeadership(target.id()).get(5, TimeUnit.SECONDS);
            awaitTrue("target leader", () ->
                    target.state() == RaftState.LEADER, 5000);
            assertThat(target.currentTerm()).isGreaterThanOrEqualTo(oldTerm + 1);
        }
    }

    @Test
    void committedDataPreservedAfterTransfer() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode leader = fixture.leader();
            RaftNode target = fixture.followerOf(leader.id());
            leader.propose(bytes("data-1")).get();
            leader.transferLeadership(target.id()).get(5, TimeUnit.SECONDS);
            awaitTrue("target leader", () ->
                    target.state() == RaftState.LEADER, 5000);
            target.propose(bytes("data-2")).get();
            awaitTrue("both applied everywhere", () ->
                    fixture.applied().contains("data-1")
                            && fixture.applied().contains("data-2"), 5000);
        }
    }

    @Test
    void clientRetriesOnNewLeader() throws Exception {
        RaftFixture fixture = RaftFixture.inProcess();
        RaftNode leader = fixture.leader();
        RaftNode target = fixture.followerOf(leader.id());
        AtomicReference<RaftNode> leaderRef = new AtomicReference<>(leader);
        AsyncProposalQueue queue = new AsyncProposalQueue(1024);
        AsyncReplicationClient client = new AsyncReplicationClient(queue, leaderRef::get);
        try {
            leader.transferLeadership(target.id()).get(5, TimeUnit.SECONDS);
            awaitTrue("target leader", () ->
                    target.state() == RaftState.LEADER, 5000);
            leaderRef.set(target);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            client.submit(bytes("after-transfer"), (index, err) -> {
                error.set(err);
                latch.countDown();
            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(error.get()).isNull();
            awaitTrue("applied", () ->
                    fixture.applied().contains("after-transfer"), 3000);
        } finally {
            client.close();
            fixture.close();
        }
    }

    @Test
    void receiveTimeoutNowStaleTermRejected() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode leader = fixture.leader();
            RaftNode follower = fixture.followerOf(leader.id());
            long term = follower.currentTerm();
            TimeoutNowResponse response = follower.receiveTimeoutNow(
                    new TimeoutNowRequest(term - 1, leader.id()));
            assertThat(response.accepted()).isFalse();
        }
    }

    @Test
    void receiveTimeoutNowTriggersElection() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode leader = fixture.leader();
            RaftNode follower = fixture.followerOf(leader.id());
            TimeoutNowResponse response = follower.receiveTimeoutNow(
                    new TimeoutNowRequest(leader.currentTerm(), leader.id()));
            assertThat(response.accepted()).isTrue();
            awaitTrue("follower elected", () ->
                    follower.state() == RaftState.LEADER, 5000);
        }
    }

    @Test
    void transferRejectedWhenTargetSuspended() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode leader = fixture.leader();
            RaftNode target = fixture.followerOf(leader.id());
            target.suspend();
            assertThat(leader.transferLeadership(target.id())
                    .get(5, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void transferBackAndForth() throws Exception {
        try (RaftFixture fixture = RaftFixture.inProcess()) {
            RaftNode first = fixture.leader();
            RaftNode second = fixture.followerOf(first.id());
            assertThat(first.transferLeadership(second.id())
                    .get(5, TimeUnit.SECONDS)).isTrue();
            awaitTrue("second leader", () ->
                    second.state() == RaftState.LEADER, 5000);
            RaftNode third = fixture.followerOf(second.id());
            assertThat(second.transferLeadership(third.id())
                    .get(5, TimeUnit.SECONDS)).isTrue();
            awaitTrue("third leader", () ->
                    third.state() == RaftState.LEADER, 5000);
        }
    }

    @Test
    void tcpTransferLeadership() throws Exception {
        try (TcpRaftFixture fixture = TcpRaftFixture.start()) {
            RaftNode leader = fixture.leader();
            RaftNode target = fixture.followerOf(leader.id());
            assertThat(leader.transferLeadership(target.id())
                    .get(5, TimeUnit.SECONDS)).isTrue();
            awaitTrue("tcp target leader", () ->
                    target.state() == RaftState.LEADER, 5000);
        }
    }

    @Test
    void leaderTransferManagerUpdatesEpoch() throws Exception {
        RaftFixture fixture = RaftFixture.inProcess();
        RaftNode leader = fixture.leader();
        RaftNode target = fixture.followerOf(leader.id());
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, leader.id());
        LeaderTransferManager manager = new LeaderTransferManager(regions,
                Map.of(new RegionId(1), leader));
        try {
            assertThat(manager.transferLeader(new RegionId(1), target.id())).isTrue();
            Region region = regions.get(new RegionId(1));
            assertThat(region.leader()).isEqualTo(target.id());
            assertThat(region.epoch().confVer()).isEqualTo(2);
        } finally {
            fixture.close();
        }
    }

    @Test
    void leaderTransferManagerRejectsNonPeer() throws Exception {
        RaftFixture fixture = RaftFixture.inProcess();
        RaftNode leader = fixture.leader();
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, leader.id());
        LeaderTransferManager manager = new LeaderTransferManager(regions,
                Map.of(new RegionId(1), leader));
        try {
            assertThatThrownBy(() -> manager.transferLeader(
                    new RegionId(1), "n9"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void leaderTransferManagerUnknownRegionThrows() {
        LeaderTransferManager manager = new LeaderTransferManager(
                new RegionManager(), Map.of());
        assertThatThrownBy(() -> manager.transferLeader(new RegionId(9), "n1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 进程内 3 节点夹具（可注入 append 丢弃）。 */
    private static final class RaftFixture implements AutoCloseable {
        private final List<RaftNode> nodes;
        private final List<String> applied;
        private final DropTransport leaderTransport;
        private volatile String dropTarget;

        private RaftFixture(List<RaftNode> nodes, List<String> applied,
                            DropTransport leaderTransport) {
            this.nodes = nodes;
            this.applied = applied;
            this.leaderTransport = leaderTransport;
        }

        private static RaftFixture inProcess() throws InterruptedException {
            List<String> applied = Collections.synchronizedList(new ArrayList<>());
            List<RaftNode> peers = new ArrayList<>();
            List<RaftNode> nodes = new ArrayList<>();
            Map<String, DropTransport> transports = new HashMap<>();
            for (String id : List.of("n1", "n2", "n3")) {
                DropTransport transport = new DropTransport(peers, id);
                RaftNode node = new RaftNode(id, transport,
                        (index, command) -> applied.add(
                                new String(command, StandardCharsets.UTF_8)),
                        ELECTION, 25, 10, new MemoryRaftLog(), null, null);
                nodes.add(node);
                transports.put(id, transport);
            }
            peers.addAll(nodes);
            RaftTestSupport.startAll(nodes.toArray(new RaftNode[0]));
            RaftNode leader = awaitLeader(nodes, 5000);
            RaftFixture fixture = new RaftFixture(
                    nodes, applied, transports.get(leader.id()));
            fixture.dropTarget = null;
            return fixture;
        }

        private RaftNode leader() throws InterruptedException {
            return awaitLeader(nodes, 5000);
        }

        private RaftNode followerOf(String leaderId) {
            for (RaftNode node : nodes) {
                if (!node.id().equals(leaderId)) {
                    return node;
                }
            }
            throw new IllegalStateException("no follower");
        }

        private void dropAppendTo(String target) {
            this.dropTarget = target;
            leaderTransport.dropTarget = target;
        }

        private void clearDrop() {
            this.dropTarget = null;
            leaderTransport.dropTarget = null;
        }

        private List<RaftNode> nodes() {
            return nodes;
        }

        private List<String> applied() {
            return applied;
        }

        @Override
        public void close() {
            for (RaftNode node : nodes) {
                node.close();
            }
        }
    }

    /** 可定向丢弃 AppendEntries 的本地传输。 */
    private static final class DropTransport implements RaftTransport {
        private final List<RaftNode> peers;
        private final String selfId;
        private volatile String dropTarget;

        private DropTransport(List<RaftNode> peers, String selfId) {
            this.peers = peers;
            this.selfId = selfId;
        }

        @Override
        public List<String> peerIds() {
            return peers.stream().map(RaftNode::id).toList();
        }

        @Override
        public CompletableFuture<VoteResponse> requestVote(
                String target, VoteRequest request) {
            return CompletableFuture.completedFuture(find(target).receive(request));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> appendEntries(
                String target, AppendEntriesRequest request) {
            if (target.equals(dropTarget)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("dropped"));
            }
            return CompletableFuture.completedFuture(find(target).receive(request));
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> installSnapshot(
                String target, InstallSnapshotRequest request) {
            return CompletableFuture.completedFuture(find(target).receive(request));
        }

        @Override
        public CompletableFuture<TimeoutNowResponse> timeoutNow(
                String target, TimeoutNowRequest request) {
            return CompletableFuture.completedFuture(
                    find(target).receiveTimeoutNow(request));
        }

        private RaftNode find(String id) {
            for (RaftNode peer : peers) {
                if (peer.id().equals(id)) {
                    return peer;
                }
            }
            throw new IllegalStateException("no peer " + id);
        }
    }

    /** TCP 单组夹具（MultiRaftEndpoint）。 */
    private static final class TcpRaftFixture implements AutoCloseable {
        private final Map<String, MultiRaftEndpoint> endpoints;
        private final List<RaftNode> nodes;

        private TcpRaftFixture(Map<String, MultiRaftEndpoint> endpoints,
                               List<RaftNode> nodes) {
            this.endpoints = endpoints;
            this.nodes = nodes;
        }

        private static TcpRaftFixture start() throws Exception {
            Map<String, InetSocketAddress> addresses = new HashMap<>();
            for (String nodeId : List.of("n1", "n2", "n3")) {
                try (ServerSocket socket = new ServerSocket(0)) {
                    addresses.put(nodeId, new InetSocketAddress(
                            "127.0.0.1", socket.getLocalPort()));
                }
            }
            Map<String, MultiRaftEndpoint> endpoints = new HashMap<>();
            Map<String, RaftGroupManager> managers = new HashMap<>();
            List<RaftNode> nodes = new ArrayList<>();
            for (String nodeId : List.of("n1", "n2", "n3")) {
                MultiRaftEndpoint endpoint = new MultiRaftEndpoint(
                        nodeId, addresses.get(nodeId).getPort(), addresses);
                endpoint.start();
                MultiRaftNode host = new MultiRaftNode(nodeId);
                RaftGroupManager manager = new RaftGroupManager(
                        nodeId, host, ELECTION, 25, 10);
                manager.createGroup("g1",
                        new MultiRaftTransport("g1", endpoint), MemTable.create());
                endpoint.register("g1", manager.raftFor("g1"));
                endpoints.put(nodeId, endpoint);
                managers.put(nodeId, manager);
                nodes.add(manager.raftFor("g1"));
            }
            for (RaftGroupManager manager : managers.values()) {
                manager.startAll();
            }
            return new TcpRaftFixture(endpoints, nodes);
        }

        private RaftNode leader() throws InterruptedException {
            return awaitLeader(nodes, 8000);
        }

        private RaftNode followerOf(String leaderId) {
            for (RaftNode node : nodes) {
                if (!node.id().equals(leaderId)) {
                    return node;
                }
            }
            throw new IllegalStateException("no follower");
        }

        @Override
        public void close() {
            for (RaftNode node : nodes) {
                node.close();
            }
            for (MultiRaftEndpoint endpoint : endpoints.values()) {
                endpoint.close();
            }
        }
    }
}
