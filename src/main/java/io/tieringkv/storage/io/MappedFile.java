package io.tieringkv.storage.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * mmap 文件映射（ADR-0026）：READ_ONLY 映射；
 * close 清引用，映射由 JDK 在 GC 时解除（不依赖 Unsafe）。
 */
public final class MappedFile implements AutoCloseable {

    private final Path path;
    private final MappedByteBuffer buffer;
    private final long size;

    private MappedFile(Path path, MappedByteBuffer buffer, long size) {
        this.path = path;
        this.buffer = buffer;
        this.size = size;
    }

    public static MappedFile open(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            return new MappedFile(path, buffer, size);
        }
    }

    public MappedByteBuffer buffer() {
        return buffer;
    }

    public long size() {
        return size;
    }

    public Path path() {
        return path;
    }

    public FileRegion region(long offset, int length) {
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.position((int) offset);
        duplicate.limit((int) offset + length);
        return new FileRegion(offset, length, duplicate.slice());
    }

    @Override
    public void close() {
        // 映射解除依赖 GC（JDK 无公开 unmap API）；清引用即释放可达性
    }
}
