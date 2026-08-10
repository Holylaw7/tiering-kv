package io.tieringkv.cluster;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 存储引擎状态机快照编解码：entry 计数 + (key, value, ttl) 序列化，
 * 供 SnapshotManager 的 source/sink 使用。
 */
public final class StorageSnapshotCodec {

    private StorageSnapshotCodec() {
    }

    public static byte[] serialize(StorageEngine engine) {
        try (StorageIterator iterator = engine.iterator();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            java.util.List<KeyValueEntry> entries = new java.util.ArrayList<>();
            while (iterator.hasNext()) {
                entries.add(iterator.next());
            }
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putInt(entries.size()).array());
            for (KeyValueEntry entry : entries) {
                long ttl = entry.expireTimestamp() >= 0
                        ? Math.max(0, entry.expireTimestamp() - System.currentTimeMillis())
                        : StorageEngine.NO_TTL;
                out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        .putInt(entry.key().length).array());
                out.write(entry.key());
                out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        .putInt(entry.value() == null ? 0 : entry.value().length).array());
                if (entry.value() != null) {
                    out.write(entry.value());
                }
                out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                        .putLong(ttl).array());
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("snapshot serialize failed", e);
        }
    }

    public static void restore(StorageEngine engine, byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int count = buffer.getInt();
        for (int i = 0; i < count; i++) {
            int keyLength = buffer.getInt();
            byte[] key = new byte[keyLength];
            buffer.get(key);
            int valueLength = buffer.getInt();
            byte[] value = new byte[valueLength];
            buffer.get(value);
            long ttl = buffer.getLong();
            engine.put(key, valueLength == 0 ? new byte[0] : value, ttl);
        }
    }
}
