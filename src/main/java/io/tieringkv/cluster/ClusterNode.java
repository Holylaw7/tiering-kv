package io.tieringkv.cluster;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.log.RaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;
import io.tieringkv.storage.StorageEngine;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 集群存储节点（ADR-0037）：Raft 组 + 复制存储。 */
public final class ClusterNode implements AutoCloseable {

    private final String id;
    private final ReplicatedStorageEngine storage;
    private final RaftNode raft;

    private ClusterNode(String id, ReplicatedStorageEngine storage) {
        this.id = id;
        this.storage = storage;
        this.raft = storage.raft();
    }

    public static ClusterNode create(
            String id,
            List<RaftNode> peers,
            StorageEngine local,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis) {
        return new ClusterNode(id, ReplicatedStorageEngine.create(
                id, peers, local, election, heartbeatIntervalMillis, tickIntervalMillis));
    }

    /** 生产构造：TCP 传输 + 持久日志/状态/快照。 */
    public static ClusterNode createPersistent(
            String id,
            RaftTransport transport,
            StorageEngine local,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis,
            RaftLog raftLog,
            RaftPersistentState persistentState,
            SnapshotManager snapshotManager) {
        return new ClusterNode(id, ReplicatedStorageEngine.create(
                id, transport, local, election, heartbeatIntervalMillis,
                tickIntervalMillis, raftLog, persistentState, snapshotManager));
    }

    public void start() {
        raft.start();
    }

    public void put(byte[] key, byte[] value) {
        storage.put(key, value);
    }

    /** 异步复制写（ADR-0050/0054）：返回 future，提交后完成。 */
    public CompletableFuture<Void> putAsync(byte[] key, byte[] value) {
        return storage.putAsync(key, value);
    }

    public byte[] get(byte[] key) {
        return storage.get(key);
    }

    public boolean delete(byte[] key) {
        return storage.delete(key);
    }

    public boolean isLeader() {
        return raft.state() == RaftState.LEADER && id.equals(raft.leaderId());
    }

    public String id() {
        return id;
    }

    public RaftNode raft() {
        return raft;
    }

    @Override
    public void close() {
        raft.close();
    }
}
