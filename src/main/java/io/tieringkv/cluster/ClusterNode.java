package io.tieringkv.cluster;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

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

    public void start() {
        raft.start();
    }

    public void put(byte[] key, byte[] value) {
        storage.put(key, value);
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
