package io.tieringkv.mvcc;

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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;

/**
 * 持久化事务日志（ADR-0081）：本地文件追加（fsync）+ Raft 提案。
 * 本地记录先落盘再进 Raft：崩溃后 replay 一定能看到已持久化的状态，
 * 避免“COMMIT 已决定但本地丢失”的 lost commit。
 */
public final class PersistentTxnJournal implements AutoCloseable {

    public static final byte[] MAGIC = {'T', 'K', 'M', 'T', 'X', 'J'};
    public static final int VERSION = 1;

    private final Path path;
    private final TxnJournal raft;
    private final DataOutputStream out;
    private boolean closed;

    public PersistentTxnJournal(Path path, TxnJournal raft) throws IOException {
        this.path = path;
        this.raft = raft;
        if (!Files.exists(path)) {
            try (OutputStream raw = Files.newOutputStream(path)) {
                raw.write(MAGIC);
                DataOutputStream header = new DataOutputStream(raw);
                header.writeInt(VERSION);
                header.flush();
            }
        }
        this.out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(path, StandardOpenOption.APPEND)));
    }

    /** 记录事务状态：本地先持久化，再经 Raft 提案（失败上抛但不回滚本地）。 */
    public CompletableFuture<Void> recordState(TxnStateRecord record) {
        try {
            byte[] payload = encode(record);
            writeRecord(payload);
            return raftRecordWithRetry(payload, 0);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 仅本地追加（决策持久化判定用）：返回本地是否落盘成功。 */
    public boolean appendLocal(TxnStateRecord record) {
        try {
            writeRecord(encode(record));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 仅 Raft 提案（决策已本地持久化后的传播，失败不致命）。 */
    public CompletableFuture<Void> propose(TxnStateRecord record) {
        return raftRecordWithRetry(encode(record), 0);
    }

    /** Raft 提案重试（leader 变更瞬时失败）：与生产写路径语义一致。 */
    private CompletableFuture<Void> raftRecordWithRetry(byte[] payload,
                                                        int attempt) {
        return raft.record(payload).handle((ignored, error) -> {
            if (error == null) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            if (attempt < 3 && error instanceof IllegalStateException) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return raftRecordWithRetry(payload, attempt + 1);
            }
            return CompletableFuture.<Void>failedFuture(error);
        }).thenCompose(future -> future);
    }

    /** 重放全部记录；尾部截断容忍，中部损坏抛错。 */
    public List<TxnStateRecord> replay() throws IOException {
        List<TxnStateRecord> records = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(
                     new BufferedInputStream(raw))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IOException("invalid txn journal magic");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("unsupported txn journal version");
            }
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException e) {
                    break; // 干净结尾
                }
                if (length <= 0 || length > 1 << 20) {
                    throw new IOException("invalid txn record length");
                }
                byte[] payload = new byte[length];
                try {
                    in.readFully(payload);
                } catch (EOFException e) {
                    break; // 尾部截断：容忍（崩溃写一半）
                }
                try {
                    records.add(decode(payload));
                } catch (IllegalStateException e) {
                    throw new IOException("corrupt txn record", e);
                }
            }
        }
        return records;
    }

    public int size() throws IOException {
        return replay().size();
    }

    public Path path() {
        return path;
    }

    private void writeRecord(byte[] payload) throws IOException {
        synchronized (out) {
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        }
    }

    /** 编码：state + txnId + startTS + commitTS + primary + mutations + CRC32。 */
    public static byte[] encode(TxnStateRecord record) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(record.state().ordinal());
            out.writeUTF(record.txnId());
            out.writeLong(record.startTS());
            out.writeLong(record.commitTS());
            writeBytes(out, record.primary());
            out.writeShort(record.mutations().size());
            for (TxnStateRecord.Mutation mutation : record.mutations()) {
                writeBytes(out, mutation.key());
                writeNullableBytes(out, mutation.value());
                out.writeBoolean(mutation.deleted());
            }
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] payload = buffer.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);
        java.io.ByteArrayOutputStream withCrc = new java.io.ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(withCrc)) {
            out.write(payload);
            out.writeInt((int) crc.getValue());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return withCrc.toByteArray();
    }

    public static TxnStateRecord decode(byte[] payload) {
        if (payload.length < 4) {
            throw new IllegalStateException("truncated txn record");
        }
        byte[] body = java.util.Arrays.copyOf(payload, payload.length - 4);
        CRC32 crc = new CRC32();
        crc.update(body);
        int expected = ((payload[payload.length - 4] & 0xFF) << 24)
                | ((payload[payload.length - 3] & 0xFF) << 16)
                | ((payload[payload.length - 2] & 0xFF) << 8)
                | (payload[payload.length - 1] & 0xFF);
        if ((int) crc.getValue() != expected) {
            throw new IllegalStateException("txn record crc mismatch");
        }
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(body))) {
            int state = in.readUnsignedByte();
            if (state >= TxnStateRecord.State.values().length) {
                throw new IllegalStateException("invalid txn state");
            }
            String txnId = in.readUTF();
            long startTS = in.readLong();
            long commitTS = in.readLong();
            byte[] primary = readBytes(in);
            int count = in.readUnsignedShort();
            List<TxnStateRecord.Mutation> mutations = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] key = readBytes(in);
                byte[] value = readNullableBytes(in);
                boolean deleted = in.readBoolean();
                mutations.add(new TxnStateRecord.Mutation(key, value, deleted));
            }
            return new TxnStateRecord(txnId,
                    TxnStateRecord.State.values()[state], startTS, commitTS,
                    primary, mutations);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes)
            throws IOException {
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void writeNullableBytes(DataOutputStream out, byte[] bytes)
            throws IOException {
        if (bytes == null) {
            out.writeShort(-1);
        } else {
            out.writeShort(bytes.length);
            out.write(bytes);
        }
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static byte[] readNullableBytes(DataInputStream in)
            throws IOException {
        int length = in.readUnsignedShort();
        if (length == 0xFFFF) {
            return null;
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            out.close();
        }
    }
}
