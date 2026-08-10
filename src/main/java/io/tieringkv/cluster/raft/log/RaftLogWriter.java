package io.tieringkv.cluster.raft.log;

import io.tieringkv.cluster.raft.LogEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** RaftLog 写入（ADR-0039）：编码单条记录并追加到文件末尾。 */
public final class RaftLogWriter {

    private RaftLogWriter() {
    }

    public static ByteBuffer writeEntry(FileChannel channel, LogEntry entry)
            throws IOException {
        ByteBuffer record = ByteBuffer.wrap(LogEntryCodec.encode(entry));
        channel.position(channel.size());
        while (record.hasRemaining()) {
            channel.write(record);
        }
        record.flip();
        return record;
    }

    /** 写入并返回记录起始偏移与缓冲区。 */
    public static Written write(FileChannel channel, LogEntry entry) throws IOException {
        long start = channel.size();
        ByteBuffer record = writeEntry(channel, entry);
        return new Written(start, record);
    }

    public record Written(long startOffset, ByteBuffer record) {
    }
}
