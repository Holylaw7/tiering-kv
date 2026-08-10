package io.tieringkv.storage.io;

import io.tieringkv.storage.cold.Block;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.nio.ByteBuffer;
import java.util.List;

/** 块解码门面（ADR-0026）：零拷贝 ByteBuffer 解码。 */
public final class BlockDecoder {

    private BlockDecoder() {
    }

    public static List<KeyValueEntry> decode(ByteBuffer data) {
        return Block.decode(data);
    }
}
