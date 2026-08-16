package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.RaftReplicationConfig;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 元数据 Raft 组（ADR-0047）：复用 RaftNode，命令按日志序 apply 到
 * MetadataState；leader 故障后自动选举，元数据持续可用。
 */
public final class MetadataRaftGroup implements AutoCloseable {

    /** 选举窗口有界等待（ADR-0353）：leader 切换瞬间不立即失败，吸收调度抖动。 */
    private static final long LEADER_WAIT_MILLIS = 1000;
    private static final long LEADER_POLL_MILLIS = 10;

    private final Map<String, RaftNode> nodes = new LinkedHashMap<>();
    private final Map<String, MetadataState> states = new LinkedHashMap<>();
    private final List<RaftNode> nodeList = new ArrayList<>();

    private MetadataRaftGroup() {
    }

    public static MetadataRaftGroup create(List<String> ids, LeaderElection election,
                                           long heartbeatIntervalMillis,
                                           long tickIntervalMillis) {
        MetadataRaftGroup group = new MetadataRaftGroup();
        List<RaftNode> peers = new ArrayList<>();
        for (String id : ids) {
            // 每个副本独立状态机（与真实 Raft 一致）：apply 顺序由各自日志保证
            MetadataState state = new MetadataState();
            group.states.put(id, state);
            RaftNode node = new RaftNode(id, peers,
                    (index, command) -> state.apply(command),
                    election, heartbeatIntervalMillis, tickIntervalMillis);
            group.nodes.put(id, node);
            group.nodeList.add(node);
        }
        peers.addAll(group.nodeList);
        return group;
    }

    /** 持久化构造（ADR-0052）：FileRaftLog + RaftPersistentState + MetadataSnapshot。 */
    public static MetadataRaftGroup createPersistent(
            List<String> ids, LeaderElection election,
            long heartbeatIntervalMillis, long tickIntervalMillis,
            Path baseDir) throws IOException {
        MetadataRaftGroup group = new MetadataRaftGroup();
        List<RaftNode> peers = new ArrayList<>();
        for (String id : ids) {
            MetadataState state = new MetadataState();
            group.states.put(id, state);
            Path nodeDir = baseDir.resolve(id);
            RaftLog log = FileRaftLog.open(nodeDir.resolve("raftlog"), Durability.SYNC);
            RaftPersistentState persistent = RaftPersistentState.open(nodeDir);
            SnapshotManager snapshot = SnapshotManager.open(
                    nodeDir.resolve("snapshot"),
                    () -> MetadataStateCodec.serialize(state),
                    data -> MetadataStateCodec.restore(state, data));
            RaftNode node = new RaftNode(id,
                    new io.tieringkv.cluster.raft.LocalRaftTransport(peers, id),
                    (index, command) -> state.apply(command),
                    election, heartbeatIntervalMillis, tickIntervalMillis,
                    log, persistent, snapshot,
                    RaftReplicationConfig.defaults(), null);
            group.nodes.put(id, node);
            group.nodeList.add(node);
        }
        peers.addAll(group.nodeList);
        return group;
    }

    public void start() {
        nodeList.forEach(RaftNode::start);
    }

    public RaftNode leader() {
        for (RaftNode node : nodeList) {
            if (node.state() == RaftState.LEADER) {
                return node;
            }
        }
        return null;
    }

    public RaftNode node(String id) {
        return nodes.get(id);
    }

    /**
     * 写命令：propose 到 leader；leader 失效时重试一次新 leader。
     * leader 暂缺（选举窗口）时先有界等待（ADR-0353），避免
     * fail-fast 造成慢 Runner / 故障切换期间的瞬时客户端失败；
     * 等待有界，满足 Phase 20「禁止客户端永久悬挂」约束。
     */
    public void write(byte[] command) {
        RaftNode leader = awaitLeader(LEADER_WAIT_MILLIS);
        if (leader == null) {
            throw new IllegalStateException("no metadata leader");
        }
        try {
            leader.propose(command).get(5, TimeUnit.SECONDS);
        } catch (Exception first) {
            RaftNode newLeader = leader();
            if (newLeader != null && newLeader != leader) {
                try {
                    newLeader.propose(command).get(5, TimeUnit.SECONDS);
                    return;
                } catch (Exception retry) {
                    throw new IllegalStateException("metadata write failed on retry", retry);
                }
            }
            throw new IllegalStateException("metadata write failed", first);
        }
    }

    /** 有界轮询等待 leader 出现；超时返回 null，中断时恢复中断位。 */
    private RaftNode awaitLeader(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            RaftNode current = leader();
            if (current != null) {
                return current;
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(LEADER_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    public MetadataState state(String nodeId) {
        return states.get(nodeId);
    }

    /** leader 副本状态（线性一致读口径）。 */
    public MetadataState leaderState() {
        RaftNode leader = leader();
        return leader == null ? null : states.get(leader.id());
    }

    public List<RaftNode> nodes() {
        return List.copyOf(nodeList);
    }

    @Override
    public void close() {
        nodeList.forEach(RaftNode::close);
    }
}
