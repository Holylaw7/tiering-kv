package io.tieringkv.cluster.raft.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** RaftLog 读取（ADR-0039）：按偏移读取完整记录缓冲区。 */
public final class RaftLogReader {

    private static final int HEADER_SIZE = 26;
    private static final int CRC_SIZE = 4;

    private RaftLogReader() {
    }

    public static ByteBuffer readRecord(FileChannel channel, long offset)
            throws IOException {
        if (offset + HEADER_SIZE + CRC_SIZE > channel.size()) {
            throw new IOException("truncated record at offset " + offset);
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        channel.read(header, offset);
        header.flip();
        int dataLength = header.getInt(22);
        int total = HEADER_SIZE + dataLength + CRC_SIZE;
        ByteBuffer record = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        int read = 0;
        while (read < total) {
            int n = channel.read(record, offset + read);
            if (n < 0) {
                throw new IOException("eof reading record");
            }
            read += n;
        }
        record.flip();
        return record;
    }
}
