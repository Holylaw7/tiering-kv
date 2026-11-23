package io.tieringkv.cluster.multiraft;

import io.tieringkv.cluster.ReplicatedStorageEngine;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.log.RaftLog;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;
import io.tieringkv.storage.StorageEngine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raft 组管理器（ADR-0058）：按 Region 创建/销毁独立 Raft 组；
 * 每个组拥有独立 StorageEngine（日志/状态/快照隔离）。
 */
public final class RaftGroupManager implements AutoCloseable {

    private final String nodeId;
    private final MultiRaftNode raftHost;
    private final LeaderElection election;
    private final long heartbeatIntervalMillis;
    private final long tickIntervalMillis;
    private final Map<String, ReplicatedStorageEngine> groups = new ConcurrentHashMap<>();

    public RaftGroupManager(String nodeId,
                            MultiRaftNode raftHost,
                            LeaderElection election,
                            long heartbeatIntervalMillis,
                            long tickIntervalMillis) {
        this.nodeId = nodeId;
        this.raftHost = raftHost;
        this.election = election;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.tickIntervalMillis = tickIntervalMillis;
    }

    /** 创建组：本地存储 + 复制适配器 + RaftNode 注册到宿主。 */
    public ReplicatedStorageEngine createGroup(String groupId,
                                               RaftTransport transport,
                                               StorageEngine local) {
        return registerGroup(groupId, ReplicatedStorageEngine.create(
                nodeId, transport, local, election, heartbeatIntervalMillis,
                tickIntervalMillis, new MemoryRaftLog(), null, null));
    }

    /** 持久化组：独立日志/状态/快照（ADR-0058 组隔离）。 */
    public ReplicatedStorageEngine createGroupPersistent(
            String groupId,
            RaftTransport transport,
            StorageEngine local,
            RaftLog raftLog,
            RaftPersistentState persistentState,
            SnapshotManager snapshotManager) {
        ReplicatedStorageEngine engine = ReplicatedStorageEngine.create(
                nodeId, transport, local, election, heartbeatIntervalMillis,
                tickIntervalMillis, raftLog, persistentState, snapshotManager);
        return registerGroup(groupId, engine);
    }

    private ReplicatedStorageEngine registerGroup(String groupId,
                                                  ReplicatedStorageEngine engine) {
        groups.put(groupId, engine);
        raftHost.register(groupId, engine.raft());
        return engine;
    }

    public void startAll() {
        raftHost.startAll();
    }

    public void destroy(String groupId) {
        groups.remove(groupId);
        raftHost.destroy(groupId);
    }

    public RaftNode raftFor(String groupId) {
        return raftHost.require(groupId);
    }

    public StorageEngine storageFor(String groupId) {
        ReplicatedStorageEngine engine = groups.get(groupId);
        if (engine == null) {
            throw new IllegalArgumentException("unknown group " + groupId);
        }
        return engine;
    }

    public int groupCount() {
        return groups.size();
    }

    public Map<String, ReplicatedStorageEngine> groups() {
        return Map.copyOf(groups);
    }

    @Override
    public void close() {
        raftHost.close();
        groups.clear();
    }
}
