package io.tieringkv.cluster.raft.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 快照管理（ADR-0040）：创建/加载/校验/恢复状态机；
 * 数据源与恢复目标由 RaftNode 注册（状态机序列化回调）。
 */
public final class SnapshotManager {

    private final Path dir;
    private final Supplier<byte[]> source;
    private final Consumer<byte[]> sink;
    private SnapshotMetadata metadata;
    private byte[] data;

    public SnapshotManager(Path dir, Supplier<byte[]> source, Consumer<byte[]> sink) {
        this.dir = dir;
        this.source = source;
        this.sink = sink;
    }

    /** 打开目录并加载已有快照；损坏快照抛出异常（由调用方决定回退策略）。 */
    public static SnapshotManager open(Path dir, Supplier<byte[]> source,
                                       Consumer<byte[]> sink) throws IOException {
        SnapshotManager manager = new SnapshotManager(dir, source, sink);
        SnapshotReader.Snapshot snapshot = SnapshotReader.read(dir);
        if (snapshot != null) {
            manager.metadata = snapshot.metadata();
            manager.data = snapshot.state();
            sink.accept(snapshot.state());
        }
        return manager;
    }

    public synchronized boolean hasSnapshot() {
        return metadata != null;
    }

    public synchronized SnapshotMetadata metadata() {
        return metadata;
    }

    public synchronized byte[] data() {
        return data == null ? null : data.clone();
    }

    /** 从当前状态机创建快照并原子写盘。 */
    public synchronized boolean create(long lastIncludedIndex, long lastIncludedTerm) {
        try {
            SnapshotMetadata newMetadata =
                    new SnapshotMetadata(lastIncludedIndex, lastIncludedTerm);
            byte[] state = source.get();
            SnapshotWriter.write(dir, newMetadata, state);
            metadata = newMetadata;
            data = state;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 安装来自 leader 的快照：校验后写盘并恢复状态机。 */
    public synchronized boolean install(long lastIncludedIndex, long lastIncludedTerm,
                                        byte[] state) {
        try {
            SnapshotMetadata newMetadata =
                    new SnapshotMetadata(lastIncludedIndex, lastIncludedTerm);
            SnapshotWriter.write(dir, newMetadata, state);
            metadata = newMetadata;
            data = state;
            sink.accept(state);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public Path directory() {
        return dir;
    }
}
