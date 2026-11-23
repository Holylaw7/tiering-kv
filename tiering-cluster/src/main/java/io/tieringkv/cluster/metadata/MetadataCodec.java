package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.sharding.ShardId;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 元数据命令二进制编解码（ADR-0047）：JOIN/LEAVE/CREATE_SHARD/... */
public final class MetadataCodec {

    private MetadataCodec() {
    }

    public static byte[] join(String nodeId) {
        byte[] id = utf8(nodeId);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + id.length).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) MetadataCommandType.JOIN.wireValue());
        buffer.putShort((short) id.length);
        buffer.put(id);
        return buffer.array();
    }

    public static byte[] leave(String nodeId) {
        byte[] id = utf8(nodeId);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + id.length).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) MetadataCommandType.LEAVE.wireValue());
        buffer.putShort((short) id.length);
        buffer.put(id);
        return buffer.array();
    }

    public static byte[] createShard(ShardId shardId, List<String> nodes, String leader) {
        int nodeBytes = nodes.stream().mapToInt(n -> 2 + utf8(n).length).sum();
        byte[] leaderBytes = leader == null ? new byte[0] : utf8(leader);
        ByteBuffer buffer = ByteBuffer.allocate(
                1 + 4 + 4 + nodeBytes + 2 + leaderBytes.length)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) MetadataCommandType.CREATE_SHARD.wireValue());
        buffer.putInt(shardId.id());
        buffer.putInt(nodes.size());
        for (String node : nodes) {
            byte[] id = utf8(node);
            buffer.putShort((short) id.length);
            buffer.put(id);
        }
        buffer.putShort((short) leaderBytes.length);
        buffer.put(leaderBytes);
        return buffer.array();
    }

    public static byte[] updateLeader(int shardId, String leader) {
        byte[] leaderBytes = utf8(leader);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + 2 + leaderBytes.length)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) MetadataCommandType.UPDATE_LEADER.wireValue());
        buffer.putInt(shardId);
        buffer.putShort((short) leaderBytes.length);
        buffer.put(leaderBytes);
        return buffer.array();
    }

    public static byte[] assignSlots(int shardCount) {
        ByteBuffer buffer = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) MetadataCommandType.ASSIGN_SLOTS.wireValue());
        buffer.putInt(shardCount);
        return buffer.array();
    }

    public static byte[] migrationStatus(int shardId, String status) {
        byte[] statusBytes = utf8(status);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + 2 + statusBytes.length)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) MetadataCommandType.MIGRATION_STATUS.wireValue());
        buffer.putInt(shardId);
        buffer.putShort((short) statusBytes.length);
        buffer.put(statusBytes);
        return buffer.array();
    }

    public static Decoded decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        MetadataCommandType type = MetadataCommandType.fromWire(buffer.get());
        return switch (type) {
            case JOIN -> new Decoded(type, readString(buffer), null, null, null, -1, -1, null);
            case LEAVE -> new Decoded(type, readString(buffer), null, null, null, -1, -1, null);
            case CREATE_SHARD -> {
                int shardId = buffer.getInt();
                int count = buffer.getInt();
                List<String> nodes = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    nodes.add(readString(buffer));
                }
                String leader = readString(buffer);
                if (leader.isEmpty()) {
                    leader = null;
                }
                yield new Decoded(type, null, new ShardId(shardId), nodes, leader,
                        shardId, -1, null);
            }
            case UPDATE_LEADER -> {
                int shardId = buffer.getInt();
                String leader = readString(buffer);
                yield new Decoded(type, null, null, null, leader, shardId, -1, null);
            }
            case ASSIGN_SLOTS -> new Decoded(type, null, null, null, null, -1,
                    buffer.getInt(), null);
            case MIGRATION_STATUS -> {
                int shardId = buffer.getInt();
                String status = readString(buffer);
                yield new Decoded(type, null, null, null, null, shardId, -1, status);
            }
        };
    }

    private static String readString(ByteBuffer buffer) {
        int length = buffer.getShort() & 0xFFFF;
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public record Decoded(
            MetadataCommandType type,
            String nodeId,
            ShardId shardId,
            List<String> nodes,
            String leader,
            int shardIdValue,
            int shardCount,
            String migrationStatus) {
    }
}
