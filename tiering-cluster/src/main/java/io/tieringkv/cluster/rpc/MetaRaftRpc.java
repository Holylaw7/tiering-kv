package io.tieringkv.cluster.rpc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 元数据 Raft RPC 编解码（ADR-0099）：提案响应（决策索引）与节点状态。
 */
public final class MetaRaftRpc {

    private MetaRaftRpc() {
    }

    public record MetaRaftStatus(String leaderId, String state, long term) {
    }

    /** 非 leader 节点拒绝提案（客户端据此重定向）。 */
    public static final class NotLeaderException
            extends IllegalStateException {
        public NotLeaderException(String message) {
            super(message);
        }
    }

    public static byte[] encodeProposeResponse(long index) {
        return ByteBuffer.allocate(8).putLong(index).array();
    }

    public static long decodeProposeResponse(byte[] payload) {
        return ByteBuffer.wrap(payload).getLong();
    }

    public static byte[] encodeStatus(MetaRaftStatus status) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(status.leaderId() == null ? "" : status.leaderId());
            out.writeUTF(status.state());
            out.writeLong(status.term());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static MetaRaftStatus decodeStatus(byte[] payload) {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            String leaderId = in.readUTF();
            return new MetaRaftStatus(
                    leaderId.isEmpty() ? null : leaderId,
                    in.readUTF(), in.readLong());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

}
