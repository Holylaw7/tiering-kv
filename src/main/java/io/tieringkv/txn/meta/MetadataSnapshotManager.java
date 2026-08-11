package io.tieringkv.txn.meta;

import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.transaction.metadata.TransactionMetadataState;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 元数据快照（ADR-0095）：序列化状态机，加速恢复。 */
public final class MetadataSnapshotManager {

    public static void snapshot(Path file, TransactionMetadataState state)
            throws IOException {
        try (OutputStream raw = Files.newOutputStream(file);
             DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(raw))) {
            out.writeInt(state.snapshot().size());
            for (io.tieringkv.transaction.metadata.TxnMetaEntry entry
                    : state.snapshot().values()) {
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
            out.flush();
        }
    }

    public static TransactionMetadataState load(Path file)
            throws IOException {
        TransactionMetadataState state = new TransactionMetadataState();
        if (!Files.exists(file)) {
            return state;
        }
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(
                     new BufferedInputStream(raw))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int length = in.readInt();
                byte[] payload = new byte[length];
                in.readFully(payload);
                state.apply(TxnMetaCodec.decode(payload));
            }
        } catch (EOFException ignored) {
            // 尾部截断容忍
        }
        return state;
    }
}
