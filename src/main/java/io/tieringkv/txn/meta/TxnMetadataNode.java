package io.tieringkv.txn.meta;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftReplicationConfig;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.MultiRaftTransport;
import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TransactionMetadataState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 元数据 Raft 节点（ADR-0095）：单节点 = RaftNode + 元数据状态机。 */
public final class TxnMetadataNode {

    private final RaftNode raft;
    private final TransactionMetadataState state = new TransactionMetadataState();
    private final Path dataDir;

    public TxnMetadataNode(String id, List<RaftNode> peers) {
        this.dataDir = null;
        this.raft = new RaftNode(id, peers,
                (index, command) -> state.apply(
                        TxnMetaCodec.decode(command).withDecisionIndex(index)),
                new LeaderElection(100, 80), 25, 10);
    }

    /**
     * 网络 + 持久化构造（ADR-0099）：接入 MultiRaftEndpoint 共享传输，
     * FileRaftLog + RaftPersistentState + SnapshotManager 落盘。
     */
    public TxnMetadataNode(String id, String groupId,
                           MultiRaftEndpoint endpoint, Path dataDir)
            throws IOException {
        this.dataDir = dataDir;
        Path nodeDir = dataDir.resolve(id);
        RaftLog log = FileRaftLog.open(nodeDir.resolve("raftlog"),
                Durability.SYNC);
        RaftPersistentState persistent = RaftPersistentState.open(nodeDir);
        SnapshotManager snapshot = SnapshotManager.open(
                nodeDir.resolve("snapshot"),
                () -> {
                    try {
                        return MetadataSnapshotManager.serialize(state);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                },
                data -> {
                    try {
                        MetadataSnapshotManager.loadInto(state, data);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
        this.raft = new RaftNode(id,
                new MultiRaftTransport(groupId, endpoint),
                (index, command) -> state.apply(
                        TxnMetaCodec.decode(command).withDecisionIndex(index)),
                new LeaderElection(100, 80), 25, 10,
                log, persistent, snapshot,
                RaftReplicationConfig.defaults(), null);
        endpoint.register(groupId, raft);
        endpoint.registerProposeHandler(groupId, raft::propose);
    }

    public RaftNode raft() {
        return raft;
    }

    public TransactionMetadataState state() {
        return state;
    }

    public CompletableFuture<Long> propose(byte[] command) {
        return raft.propose(command);
    }

    public void start() {
        raft.start();
    }

    public void close() {
        raft.close();
    }

    /** 持久化根目录（ADR-0099）：重启/快照测试复用。 */
    public Path stateDir() {
        return dataDir;
    }
}
