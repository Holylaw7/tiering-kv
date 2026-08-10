package io.tieringkv.cluster;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.log.RaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * 存储复制适配器（ADR-0037）：写经 Raft 日志复制后 apply 本地引擎；
 * 读走本地。不修改 MemTable/WAL/SSTable。
 */
public final class ReplicatedStorageEngine implements StorageEngine {

    private final StorageEngine local;
    private RaftNode raft;

    private ReplicatedStorageEngine(StorageEngine local) {
        this.local = local;
    }

    public static ReplicatedStorageEngine create(
            String id,
            java.util.List<RaftNode> peers,
            StorageEngine local,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis) {
        return create(id, new io.tieringkv.cluster.raft.LocalRaftTransport(peers, id),
                local, election, heartbeatIntervalMillis, tickIntervalMillis,
                new io.tieringkv.cluster.raft.log.MemoryRaftLog(), null, null);
    }

    /** 生产构造：指定传输、持久日志、持久状态与快照。 */
    public static ReplicatedStorageEngine create(
            String id,
            RaftTransport transport,
            StorageEngine local,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis,
            RaftLog raftLog,
            RaftPersistentState persistentState,
            SnapshotManager snapshotManager) {
        ReplicatedStorageEngine engine = new ReplicatedStorageEngine(local);
        engine.raft = new RaftNode(
                id, transport, engine::applyLocal, election,
                heartbeatIntervalMillis, tickIntervalMillis,
                raftLog, persistentState, snapshotManager);
        return engine;
    }

    public RaftNode raft() {
        return raft;
    }

    private void applyLocal(long index, byte[] command) {
        Command decoded = decode(command);
        if (decoded.type() == CommandType.PUT) {
            local.put(decoded.key(), decoded.value(), decoded.ttlMillis());
        } else {
            local.delete(decoded.key());
        }
    }

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, NO_TTL);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        propose(encode(CommandType.PUT, key, value, ttlMillis));
    }

    @Override
    public byte[] get(byte[] key) {
        return local.get(key);
    }

    @Override
    public boolean delete(byte[] key) {
        propose(encode(CommandType.DELETE, key, null, -1));
        return true;
    }

    @Override
    public boolean exists(byte[] key) {
        return local.exists(key);
    }

    @Override
    public StorageIterator iterator() {
        return local.iterator();
    }

    @Override
    public long size() {
        return local.size();
    }

    private void propose(byte[] command) {
        try {
            raft.propose(command).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("raft replication failed", e);
        }
    }

    private enum CommandType {
        PUT,
        DELETE
    }

    private record Command(CommandType type, byte[] key, byte[] value, long ttlMillis) {
    }

    private static byte[] encode(CommandType type, byte[] key, byte[] value, long ttlMillis) {
        byte[] valueBytes = value == null ? new byte[0] : value;
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + key.length + 4 + valueBytes.length + 8)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put(type == CommandType.PUT ? (byte) 1 : (byte) 2);
        buffer.putInt(key.length);
        buffer.put(key);
        buffer.putInt(valueBytes.length);
        buffer.put(valueBytes);
        buffer.putLong(ttlMillis);
        return buffer.array();
    }

    private static Command decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        byte type = buffer.get();
        byte[] key = new byte[buffer.getInt()];
        buffer.get(key);
        byte[] value = new byte[buffer.getInt()];
        buffer.get(value);
        long ttl = buffer.getLong();
        return new Command(type == 1 ? CommandType.PUT : CommandType.DELETE,
                key, type == 1 ? value : null, ttl);
    }
}
