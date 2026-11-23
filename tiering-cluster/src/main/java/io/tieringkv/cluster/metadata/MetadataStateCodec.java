package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.sharding.ShardGroup;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** MetadataState 快照编解码（ADR-0052）：节点 / slot 表 / 分片组 / 迁移状态。 */
public final class MetadataStateCodec {

    private MetadataStateCodec() {
    }

    public static byte[] serialize(MetadataState state) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<String> nodes = new ArrayList<>(state.nodes().nodes());
            writeInt(out, nodes.size());
            for (String node : nodes) {
                writeBytes(out, node.getBytes(StandardCharsets.UTF_8));
            }
            Map<Integer, Integer> slots = state.topology().slotTable().snapshot();
            writeInt(out, slots.size());
            for (Map.Entry<Integer, Integer> entry : slots.entrySet()) {
                writeInt(out, entry.getKey());
                writeInt(out, entry.getValue());
            }
            List<ShardGroup> shards = state.topology().shardRegistry().all();
            writeInt(out, shards.size());
            for (ShardGroup group : shards) {
                writeInt(out, group.shardId().id());
                writeBytes(out, group.leader() == null
                        ? new byte[0] : group.leader().getBytes(StandardCharsets.UTF_8));
                writeInt(out, group.nodes().size());
                for (String node : group.nodes()) {
                    writeBytes(out, node.getBytes(StandardCharsets.UTF_8));
                }
            }
            Map<Integer, String> migrations = state.migrationStatus();
            writeInt(out, migrations.size());
            for (Map.Entry<Integer, String> entry : migrations.entrySet()) {
                writeInt(out, entry.getKey());
                writeBytes(out, entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("metadata snapshot serialize failed", e);
        }
    }

    public static void restore(MetadataState state, byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int nodeCount = buffer.getInt();
        for (int i = 0; i < nodeCount; i++) {
            state.apply(MetadataCodec.join(readString(buffer)));
        }
        int slotCount = buffer.getInt();
        for (int i = 0; i < slotCount; i++) {
            int slot = buffer.getInt();
            int shard = buffer.getInt();
            state.topology().slotTable().reassign(slot, shard);
        }
        int shardCount = buffer.getInt();
        for (int i = 0; i < shardCount; i++) {
            int shardId = buffer.getInt();
            String leader = readString(buffer);
            int memberCount = buffer.getInt();
            List<String> members = new ArrayList<>();
            for (int j = 0; j < memberCount; j++) {
                members.add(readString(buffer));
            }
            state.apply(MetadataCodec.createShard(
                    new io.tieringkv.cluster.sharding.ShardId(shardId), members,
                    leader.isEmpty() ? null : leader));
        }
        int migrationCount = buffer.getInt();
        for (int i = 0; i < migrationCount; i++) {
            int shardId = buffer.getInt();
            String status = readString(buffer);
            state.apply(MetadataCodec.migrationStatus(shardId, status));
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putInt(value).array());
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        writeInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static String readString(ByteBuffer buffer) {
        int length = buffer.getInt();
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
