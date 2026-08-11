package io.tieringkv.txn.meta;

import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.transaction.metadata.TransactionMetadataState;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 元数据快照（ADR-0095）：序列化状态机，加速恢复。 */
public final class MetadataSnapshotManager {

    public static void snapshot(Path file, TransactionMetadataState state)
            throws IOException {
        Files.write(file, serialize(state));
    }

    public static TransactionMetadataState load(Path file)
            throws IOException {
        TransactionMetadataState state = new TransactionMetadataState();
        if (!Files.exists(file)) {
            return state;
        }
        loadInto(state, Files.readAllBytes(file));
        return state;
    }

    /** 序列化为字节（ADR-0099）：供 Raft SnapshotManager 状态机快照复用。 */
    public static byte[] serialize(TransactionMetadataState state)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(bytes))) {
            Map<String, io.tieringkv.transaction.metadata.TxnMetaEntry>
                    entries = state.snapshot();
            out.writeInt(entries.size());
            for (io.tieringkv.transaction.metadata.TxnMetaEntry entry
                    : entries.values()) {
                TxnMetaCommand command = new TxnMetaCommand(
                        io.tieringkv.transaction.metadata.TxnMetaCommand.Type
                                .REGISTER,
                        entry.txnId(), entry.primary(), entry.startTS(),
                        entry.commitTS(), entry.decisionIndex(),
                        entry.state().name(), -1, entry.regionMutations());
                byte[] payload = TxnMetaCodec.encode(command);
                out.writeInt(payload.length);
                out.write(payload);
            }
            Map<String, io.tieringkv.transaction.lifecycle.TxnLifecycleRecord>
                    records = state.lifecycleSnapshot();
            out.writeInt(records.size());
            for (io.tieringkv.transaction.lifecycle.TxnLifecycleRecord record
                    : records.values()) {
                TxnMetaCommand command = new TxnMetaCommand(
                        io.tieringkv.transaction.metadata.TxnMetaCommand.Type
                                .LIFECYCLE,
                        record.txnId(), null, record.startTS(), 0,
                        record.decisionIndex(), record.state().name(),
                        record.expireAtMillis(), Map.of());
                byte[] payload = TxnMetaCodec.encode(command);
                out.writeInt(payload.length);
                out.write(payload);
            }
            out.flush();
        }
        return bytes.toByteArray();
    }

    /** 反序列化到既有状态（ADR-0099）：快照安装/重启恢复替换状态内容。 */
    public static void loadInto(TransactionMetadataState state,
                                byte[] payload) throws IOException {
        TransactionMetadataState restored = new TransactionMetadataState();
        try (InputStream raw = new ByteArrayInputStream(payload);
             DataInputStream in = new DataInputStream(
                     new BufferedInputStream(raw))) {
            readAll(in, restored);
        } catch (EOFException ignored) {
            // 尾部截断容忍
        }
        state.copyFrom(restored);
    }

    private static void readAll(DataInputStream in,
                                TransactionMetadataState state)
            throws IOException {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            int length = in.readInt();
            byte[] payload = new byte[length];
            in.readFully(payload);
            state.apply(TxnMetaCodec.decode(payload));
        }
        int lifecycleCount = in.readInt();
        for (int i = 0; i < lifecycleCount; i++) {
            int length = in.readInt();
            byte[] payload = new byte[length];
            in.readFully(payload);
            state.apply(TxnMetaCodec.decode(payload));
        }
    }
}
