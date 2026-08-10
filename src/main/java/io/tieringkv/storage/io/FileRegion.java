package io.tieringkv.storage.io;

import java.nio.ByteBuffer;

/** 映射文件区域（ADR-0026）：offset + length + 视图切片。 */
public record FileRegion(long offset, int length, ByteBuffer view) {
}
