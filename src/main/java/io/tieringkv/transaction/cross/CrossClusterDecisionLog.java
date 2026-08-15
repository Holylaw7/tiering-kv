package io.tieringkv.transaction.cross;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * 跨集群事务决策日志（ADR-0339）：'CCDC' magic + 追加 + CRC32C，
 * 尾部损坏容忍（与 ADR-0109 GeoDecisionLog 同款模式）。
 *
 * <p>payload：txnId + decision + commitTS + mutations
 * （key/value/deleted），恢复时可重放 COMMIT。
 */
public final class CrossClusterDecisionLog {

    private static final int MAGIC = 0x43434443; // 'CCDC'
    private static final int MAX_MUTATIONS = 1_000_000;
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private final Path file;

    private CrossClusterDecisionLog(Path file) {
        this.file = file;
    }

    public static CrossClusterDecisionLog open(Path dir)
            throws IOException {
        Files.createDirectories(dir);
        return new CrossClusterDecisionLog(
                dir.resolve("cross-cluster-decisions.log"));
    }

    public synchronized void append(CrossClusterDecision decision)
            throws IOException {
        byte[] payload = encode(decision);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)))) {
            out.writeInt(MAGIC);
            out.writeInt(payload.length);
            out.write(payload);
            CRC32C crc = new CRC32C();
            crc.update(payload);
            out.writeInt((int) crc.getValue());
            out.flush();
        }
    }

    public synchronized List<CrossClusterDecision> readAll()
            throws IOException {
        List<CrossClusterDecision> decisions = new ArrayList<>();
        if (!Files.exists(file)) {
            return decisions;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            while (true) {
                int magic;
                try {
                    magic = in.readInt();
                } catch (EOFException e) {
                    return decisions;
                }
                if (magic != MAGIC) {
                    return decisions; // 尾部损坏容忍
                }
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException e) {
                    return decisions;
                }
                if (length < 0 || length > MAX_BYTES) {
                    return decisions;
                }
                byte[] payload = new byte[length];
                try {
                    in.readFully(payload);
                } catch (EOFException e) {
                    return decisions;
                }
                CRC32C crc = new CRC32C();
                crc.update(payload);
                int storedCrc;
                try {
                    storedCrc = in.readInt();
                } catch (EOFException e) {
                    return decisions;
                }
                if (storedCrc != (int) crc.getValue()) {
                    return decisions;
                }
                decisions.add(decode(payload));
            }
        }
    }

    private static byte[] encode(CrossClusterDecision decision)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(decision.txnId());
            out.writeByte(decision.decision().ordinal());
            out.writeLong(decision.commitTS());
            out.writeInt(decision.mutations().size());
            for (TxnMessages.Mutation mutation
                    : decision.mutations()) {
                writeBytes(out, mutation.key());
                writeNullableBytes(out, mutation.value());
                out.writeBoolean(mutation.deleted());
            }
            out.flush();
        }
        return bytes.toByteArray();
    }

    private static CrossClusterDecision decode(byte[] payload)
            throws IOException {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            String txnId = in.readUTF();
            int decisionOrdinal = in.readUnsignedByte();
            if (decisionOrdinal >= CrossClusterDecision.Decision
                    .values().length) {
                throw new IOException("invalid decision ordinal");
            }
            long commitTS = in.readLong();
            int count = in.readInt();
            if (count < 0 || count > MAX_MUTATIONS) {
                throw new IOException("invalid mutation count");
            }
            List<TxnMessages.Mutation> mutations =
                    new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] key = readBytes(in);
                byte[] value = readNullableBytes(in);
                boolean deleted = in.readBoolean();
                mutations.add(new TxnMessages.Mutation(key, value,
                        deleted));
            }
            return new CrossClusterDecision(txnId,
                    CrossClusterDecision.Decision.values()[
                            decisionOrdinal],
                    commitTS, mutations);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value)
            throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static void writeNullableBytes(DataOutputStream out,
                                           byte[] value)
            throws IOException {
        if (value == null) {
            out.writeInt(-1);
        } else {
            writeBytes(out, value);
        }
    }

    private static byte[] readBytes(DataInputStream in)
            throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_BYTES) {
            throw new IOException("invalid byte length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static byte[] readNullableBytes(DataInputStream in)
            throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        if (length > MAX_BYTES) {
            throw new IOException("invalid byte length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
