package io.tieringkv.cluster.multiraft;

import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多 Raft 宿主（ADR-0058）：单进程内并行运行多个 RaftNode，
 * 日志/状态/快照按组隔离；生命周期统一管理。
 */
public final class MultiRaftNode implements AutoCloseable {

    private final String nodeId;
    private final Map<String, RaftNode> groups = new ConcurrentHashMap<>();

    public MultiRaftNode(String nodeId) {
        this.nodeId = nodeId;
    }

    public String nodeId() {
        return nodeId;
    }

    public void register(String groupId, RaftNode raft) {
        if (groups.putIfAbsent(groupId, raft) != null) {
            throw new IllegalArgumentException("group already exists: " + groupId);
        }
    }

    public RaftNode get(String groupId) {
        return groups.get(groupId);
    }

    public void startAll() {
        for (RaftNode raft : groups.values()) {
            raft.start();
        }
    }

    public void start(String groupId) {
        RaftNode raft = require(groupId);
        raft.start();
    }

    /** 销毁单个组：关闭调度器与日志，不影响其他组。 */
    public void destroy(String groupId) {
        RaftNode raft = groups.remove(groupId);
        if (raft != null) {
            raft.close();
        }
    }

    public RaftNode require(String groupId) {
        RaftNode raft = groups.get(groupId);
        if (raft == null) {
            throw new IllegalArgumentException("unknown group " + groupId);
        }
        return raft;
    }

    public int groupCount() {
        return groups.size();
    }

    public Set<String> groupIds() {
        return Set.copyOf(groups.keySet());
    }

    public Map<String, RaftState> states() {
        Map<String, RaftState> result = new ConcurrentHashMap<>();
        groups.forEach((groupId, raft) -> result.put(groupId, raft.state()));
        return Map.copyOf(result);
    }

    @Override
    public void close() {
        for (RaftNode raft : groups.values()) {
            raft.close();
        }
        groups.clear();
    }
}
