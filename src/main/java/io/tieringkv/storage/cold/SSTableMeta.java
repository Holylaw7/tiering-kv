package io.tieringkv.storage.cold;

import java.nio.file.Path;
import java.util.Arrays;

/** SSTable 元数据（Manifest 条目，ADR-0018）。 */
public record SSTableMeta(
        long id,
        String fileName,
        long entryCount,
        long fileSize,
        byte[] firstKey,
        byte[] lastKey) {

    public Path path(Path directory) {
        return directory.resolve(fileName);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SSTableMeta that
                && id == that.id
                && fileName.equals(that.fileName)
                && entryCount == that.entryCount
                && fileSize == that.fileSize
                && Arrays.equals(firstKey, that.firstKey)
                && Arrays.equals(lastKey, that.lastKey);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(id);
        result = 31 * result + fileName.hashCode();
        result = 31 * result + Long.hashCode(entryCount);
        result = 31 * result + Long.hashCode(fileSize);
        result = 31 * result + Arrays.hashCode(firstKey);
        result = 31 * result + Arrays.hashCode(lastKey);
        return result;
    }
}
