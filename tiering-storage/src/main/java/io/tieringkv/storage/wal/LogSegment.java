package io.tieringkv.storage.wal;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 单个 WAL 段文件（ADR-0014）：追加写 + flush/force + 滚动前关闭。 */
public final class LogSegment implements AutoCloseable {

    private final long sequence;
    private final Path path;
    private OutputStream out;
    private FileChannel channel;
    private long size;

    private LogSegment(long sequence, Path path, OutputStream out, FileChannel channel, long size) {
        this.sequence = sequence;
        this.path = path;
        this.out = out;
        this.channel = channel;
        this.size = size;
    }

    public static LogSegment openOrCreate(Path directory, long sequence) throws IOException {
        Path path = directory.resolve(String.format("%06d.log", sequence));
        FileOutputStream fileOut = new FileOutputStream(path.toFile(), true);
        OutputStream out = new BufferedOutputStream(fileOut, 64 * 1024);
        return new LogSegment(sequence, path, out, fileOut.getChannel(),
                Files.size(path));
    }

    public void append(byte[] record) throws IOException {
        out.write(record);
        size += record.length;
    }

    public void flush() throws IOException {
        out.flush();
    }

    /** flush + fsync（FileChannel.force）。 */
    public void force() throws IOException {
        out.flush();
        channel.force(true);
    }

    public long sequence() {
        return sequence;
    }

    public Path path() {
        return path;
    }

    public long size() {
        return size;
    }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.flush();
            out.close();
            out = null;
            channel = null;
        }
    }
}
