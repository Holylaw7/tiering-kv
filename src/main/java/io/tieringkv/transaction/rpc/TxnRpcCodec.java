package io.tieringkv.transaction.rpc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 事务 RPC 编解码（ADR-0083）：紧凑二进制 + 长度前缀。 */
public final class TxnRpcCodec {

    private TxnRpcCodec() {
    }

    public static byte[] encodePrewrite(TxnMessages.Prewrite request) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(request.txnId());
            out.writeLong(request.startTS());
            writeBytes(out, request.primary());
            out.writeShort(request.mutations().size());
            for (TxnMessages.Mutation mutation : request.mutations()) {
                writeBytes(out, mutation.key());
                writeNullable(out, mutation.value());
                out.writeBoolean(mutation.deleted());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TxnMessages.Prewrite decodePrewrite(byte[] payload) {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            String txnId = in.readUTF();
            long startTS = in.readLong();
            byte[] primary = readBytes(in);
            int count = in.readUnsignedShort();
            List<TxnMessages.Mutation> mutations = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] key = readBytes(in);
                byte[] value = readNullable(in);
                boolean deleted = in.readBoolean();
                mutations.add(new TxnMessages.Mutation(key, value, deleted));
            }
            return new TxnMessages.Prewrite(txnId, startTS, primary,
                    mutations);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeCommit(TxnMessages.Commit request) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(request.txnId());
            out.writeLong(request.startTS());
            out.writeLong(request.commitTS());
            writeBytes(out, request.primary());
            out.writeShort(request.mutations().size());
            for (TxnMessages.Mutation mutation : request.mutations()) {
                writeBytes(out, mutation.key());
                writeNullable(out, mutation.value());
                out.writeBoolean(mutation.deleted());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TxnMessages.Commit decodeCommit(byte[] payload) {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            String txnId = in.readUTF();
            long startTS = in.readLong();
            long commitTS = in.readLong();
            byte[] primary = readBytes(in);
            int count = in.readUnsignedShort();
            List<TxnMessages.Mutation> mutations = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] key = readBytes(in);
                byte[] value = readNullable(in);
                boolean deleted = in.readBoolean();
                mutations.add(new TxnMessages.Mutation(key, value, deleted));
            }
            return new TxnMessages.Commit(txnId, startTS, commitTS,
                    primary, mutations);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeRollback(TxnMessages.Rollback request) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(request.txnId());
            out.writeLong(request.startTS());
            writeBytes(out, request.primary());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TxnMessages.Rollback decodeRollback(byte[] payload) {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            return new TxnMessages.Rollback(in.readUTF(), in.readLong(),
                    readBytes(in));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeHeartbeat(TxnMessages.Heartbeat request) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(request.txnId());
            out.writeLong(request.startTS());
            out.writeLong(request.ttlMillis());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TxnMessages.Heartbeat decodeHeartbeat(byte[] payload) {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            return new TxnMessages.Heartbeat(in.readUTF(), in.readLong(),
                    in.readLong());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeCheckStatus(TxnMessages.CheckStatus request) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(request.txnId());
            out.writeLong(request.startTS());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TxnMessages.CheckStatus decodeCheckStatus(byte[] payload) {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            return new TxnMessages.CheckStatus(in.readUTF(), in.readLong());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeResolveLock(TxnMessages.ResolveLock request) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(request.txnId());
            out.writeLong(request.startTS());
            out.writeLong(request.commitTS());
            writeBytes(out, request.primary());
            out.writeBoolean(request.rollback());
            out.writeShort(request.mutations().size());
            for (TxnMessages.Mutation mutation : request.mutations()) {
                writeBytes(out, mutation.key());
                writeNullable(out, mutation.value());
                out.writeBoolean(mutation.deleted());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TxnMessages.ResolveLock decodeResolveLock(byte[] payload) {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            String txnId = in.readUTF();
            long startTS = in.readLong();
            long commitTS = in.readLong();
            byte[] primary = readBytes(in);
            boolean rollback = in.readBoolean();
            int count = in.readUnsignedShort();
            List<TxnMessages.Mutation> mutations = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] key = readBytes(in);
                byte[] value = readNullable(in);
                boolean deleted = in.readBoolean();
                mutations.add(new TxnMessages.Mutation(key, value, deleted));
            }
            return new TxnMessages.ResolveLock(txnId, startTS, commitTS,
                    primary, mutations, rollback);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeResponse(TxnMessages.Response response) {
        byte[] message = response.message() == null
                ? new byte[0] : response.message().getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = new byte[1 + message.length];
        bytes[0] = (byte) response.status().ordinal();
        System.arraycopy(message, 0, bytes, 1, message.length);
        return bytes;
    }

    public static TxnMessages.Response decodeResponse(byte[] payload) {
        if (payload.length < 1) {
            return TxnMessages.Response.error("empty response");
        }
        int ordinal = payload[0] & 0xFF;
        if (ordinal >= TxnMessages.Status.values().length) {
            return TxnMessages.Response.error("invalid status");
        }
        String message = new String(payload, 1, payload.length - 1,
                java.nio.charset.StandardCharsets.UTF_8);
        return new TxnMessages.Response(
                TxnMessages.Status.values()[ordinal], message);
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes)
            throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeNullable(DataOutputStream out, byte[] bytes)
            throws IOException {
        if (bytes == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static byte[] readNullable(DataInputStream in)
            throws IOException {
        int length = in.readInt();
        if (length == -1) {
            return null;
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
