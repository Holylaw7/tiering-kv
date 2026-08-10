package io.tieringkv.cache.block;

import java.nio.ByteBuffer;

/** 缓存条目（ADR-0028）：池化 DirectByteBuffer（position 0, limit=size）。 */
public record CacheEntry(ByteBuffer buffer) {
}
