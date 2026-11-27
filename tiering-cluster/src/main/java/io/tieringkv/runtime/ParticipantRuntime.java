package io.tieringkv.runtime;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.rpc.TxnParticipantRpcHandler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Participant 运行时（ADR-0093 / v6.1.0 A1）：托管 Region 事务参与者与原子快照落盘/恢复。 */
public final class ParticipantRuntime {

    private static final Logger log = LoggerFactory.getLogger(ParticipantRuntime.class);
    private static final int SNAPSHOT_MAGIC = 0x544B5653; // "TKVS"
    private static final int SNAPSHOT_VERSION = 1;

    private ParticipantRuntime() {
    }

    public static void start(Map<String, String> options) throws Exception {
        RunningParticipant running = startServer(options);
        log.info("ParticipantRuntime {} region={} listening on {} data={} restored={}",
                running.nodeId(), running.regionId(), running.boundPort(),
                running.snapshotFile().getParent(), running.restoredEntries());
        Runtime.getRuntime().addShutdownHook(new Thread(running::close));
        new CountDownLatch(1).await();
    }

    /** 可测试启停句柄：启动 Participant RPC 端点，自动加载已有快照并注册原子落盘回调。 */
    public static RunningParticipant startServer(Map<String, String> options) throws Exception {
        String nodeId = TxnRuntimeMain.require(options, "node-id");
        String regionId = TxnRuntimeMain.require(options, "region-id");
        int rpcPort = TxnRuntimeMain.port(options, "rpc-port", 7100);
        String dataDir = options.getOrDefault("data-dir", "/data");
        Path snapshotFile = snapshotPath(Path.of(dataDir), regionId);
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        int restored = loadSnapshotIfPresent(snapshotFile, engine);
        TransactionParticipant participant = new TransactionParticipant(
                regionId, engine, new LockTable(), 60_000, null,
                snapshot -> writeSnapshotAtomically(snapshotFile, snapshot));
        MultiRaftEndpoint endpoint = new MultiRaftEndpoint(nodeId, rpcPort,
                Map.of(nodeId, new InetSocketAddress("0.0.0.0", rpcPort)));
        endpoint.registerTxnHandler(regionId,
                new TxnParticipantRpcHandler(participant));
        endpoint.start();
        return new RunningParticipant(nodeId, regionId, endpoint, participant, snapshotFile, restored);
    }

    static Path snapshotPath(Path dataDir, String regionId) {
        String safe = regionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return dataDir.resolve("region-" + safe + "-checkpoint.snap");
    }

    static int writeSnapshotAtomically(Path target, Map<byte[], byte[]> entries) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(SNAPSHOT_MAGIC);
            out.writeInt(SNAPSHOT_VERSION);
            out.writeInt(entries.size());
            for (Map.Entry<byte[], byte[]> entry : entries.entrySet()) {
                byte[] key = entry.getKey();
                byte[] val = entry.getValue() == null ? new byte[0] : entry.getValue();
                out.writeInt(key.length);
                out.write(key);
                out.writeInt(val.length);
                out.write(val);
            }
            out.flush();
            fos.getFD().sync();
        }
        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return entries.size();
    }

    static int loadSnapshotIfPresent(Path snapshotFile, MvccStorageEngine engine) throws IOException {
        if (!Files.isRegularFile(snapshotFile)) {
            return 0;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(snapshotFile)))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != SNAPSHOT_MAGIC || version != SNAPSHOT_VERSION) {
                throw new IOException("unsupported participant snapshot header: magic="
                        + Integer.toHexString(magic) + " version=" + version);
            }
            int count = in.readInt();
            if (count < 0) {
                throw new IOException("corrupted participant snapshot entry count: " + count);
            }
            for (int i = 0; i < count; i++) {
                int keyLen = in.readInt();
                byte[] key = new byte[keyLen];
                in.readFully(key);
                int valLen = in.readInt();
                byte[] val = new byte[valLen];
                in.readFully(val);
                engine.putVersion(key, val, 1L, 1L, WriteType.PUT);
            }
            return count;
        }
    }

    public static final class RunningParticipant implements AutoCloseable {
        private final String nodeId;
        private final String regionId;
        private final MultiRaftEndpoint endpoint;
        private final TransactionParticipant participant;
        private final Path snapshotFile;
        private final int restoredEntries;

        RunningParticipant(String nodeId, String regionId, MultiRaftEndpoint endpoint,
                           TransactionParticipant participant, Path snapshotFile,
                           int restoredEntries) {
            this.nodeId = nodeId;
            this.regionId = regionId;
            this.endpoint = endpoint;
            this.participant = participant;
            this.snapshotFile = snapshotFile;
            this.restoredEntries = restoredEntries;
        }

        public String nodeId() {
            return nodeId;
        }

        public String regionId() {
            return regionId;
        }

        public int boundPort() {
            return endpoint.boundPort();
        }

        public TransactionParticipant participant() {
            return participant;
        }

        public Path snapshotFile() {
            return snapshotFile;
        }

        public int restoredEntries() {
            return restoredEntries;
        }

        @Override
        public void close() {
            try {
                endpoint.close();
            } catch (Exception ignored) {
                // 停机路径尽力释放端口
            }
        }
    }
}
