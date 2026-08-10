package io.tieringkv.storage.io;

import io.tieringkv.storage.cold.DiskIterator;
import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.cold.SSTableReader;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.io.IOException;
import java.nio.file.Path;

/**
 * FileChannel baseline 读取器（ADR-0026）：保留现状堆拷贝路径，
 * 用于 benchmark 对比与降级。
 */
public final class FileChannelSSTableReader implements AutoCloseable {

    private final SSTableReader delegate;

    private FileChannelSSTableReader(SSTableReader delegate) {
        this.delegate = delegate;
    }

    public static FileChannelSSTableReader open(SSTableMeta meta, Path directory) throws IOException {
        return new FileChannelSSTableReader(SSTableReader.open(meta, directory));
    }

    public KeyValueEntry get(byte[] key) throws IOException {
        return delegate.get(key);
    }

    public boolean mightContain(byte[] key) {
        return delegate.mightContain(key);
    }

    public DiskIterator iterator() throws IOException {
        return delegate.iterator();
    }

    public SSTableMeta meta() {
        return delegate.meta();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
