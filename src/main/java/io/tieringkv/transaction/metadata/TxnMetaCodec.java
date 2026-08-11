package io.tieringkv.transaction.metadata;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 元数据命令编解码（ADR-0084）。 */
public final class TxnMetaCodec {

    private TxnMetaCodec() {
    }

    public static byte[] encode(TxnMetaCommand command) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(command.type().ordinal());
            out.writeUTF(command.txnId());
            writeBytes(out, command.primary() == null
                    ? new byte[0] : command.primary());
            out.writeLong(command.startTS());
            out.writeLong(command.commitTS());
            out.writeLong(command.decisionIndex());
            // 状态名以 UTF 直存：快照可能携带 TxnMetaEntry.State
            // （REGISTERED/PREPARED/...），LIFECYCLE 命令携带
            // TxnLifecycleState（ACTIVE/PREWRITE/...），两种枚举无法
            // 共用 ordinal 空间。
            out.writeBoolean(command.lifecycleState() != null);
            if (command.lifecycleState() != null) {
                out.writeUTF(command.lifecycleState());
            }
            out.writeLong(command.expireAtMillis());
            Map<String, List<TxnMessages.Mutation>> regions =
                    command.regionMutations() == null
                            ? Map.of() : command.regionMutations();
            out.writeShort(regions.size());
            for (Map.Entry<String, List<TxnMessages.Mutation>> entry
                    : regions.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeShort(entry.getValue().size());
                for (TxnMessages.Mutation mutation : entry.getValue()) {
                    writeBytes(out, mutation.key());
                    writeNullable(out, mutation.value());
                    out.writeBoolean(mutation.deleted());
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TxnMetaCommand decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            int type = in.readUnsignedByte();
            String txnId = in.readUTF();
            byte[] primary = readBytes(in);
            long startTS = in.readLong();
            long commitTS = in.readLong();
            long decisionIndex = in.readLong();
            String lifecycleState = in.readBoolean() ? in.readUTF() : null;
            long expireAtMillis = in.readLong();
            int regionCount = in.readUnsignedShort();
            Map<String, List<TxnMessages.Mutation>> regions =
                    new LinkedHashMap<>();
            for (int r = 0; r < regionCount; r++) {
                String region = in.readUTF();
                int mutationCount = in.readUnsignedShort();
                List<TxnMessages.Mutation> mutations = new ArrayList<>();
                for (int m = 0; m < mutationCount; m++) {
                    byte[] key = readBytes(in);
                    byte[] value = readNullable(in);
                    boolean deleted = in.readBoolean();
                    mutations.add(new TxnMessages.Mutation(
                            key, value, deleted));
                }
                regions.put(region, mutations);
            }
            return new TxnMetaCommand(
                    TxnMetaCommand.Type.values()[type], txnId, primary,
                    startTS, commitTS, decisionIndex, lifecycleState,
                    expireAtMillis, regions);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes)
            throws IOException {
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void writeNullable(DataOutputStream out, byte[] bytes)
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

    private static byte[] readNullable(DataInputStream in)
            throws IOException {
        int length = in.readUnsignedShort();
        if (length == 0xFFFF) {
            return null;
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
