package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 元数据 Raft 组（ADR-0047）：复用 RaftNode，命令按日志序 apply 到
 * MetadataState；leader 故障后自动选举，元数据持续可用。
 */
public final class MetadataRaftGroup implements AutoCloseable {

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

    /** 写命令：propose 到 leader；leader 失效时重试一次新 leader。 */
    public void write(byte[] command) {
        RaftNode leader = leader();
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
