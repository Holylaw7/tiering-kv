package io.tieringkv.cdc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/** CDC 日志（ADR-0105）：分段追加 + CRC32C + 尾部损坏容忍。 */
public final class CdcLog {

    private static final int MAGIC = 0x54434443; // 'TCDC'
    private static final byte VERSION = 1;
    private static final String SEGMENT_PREFIX = "cdc-";
    private static final String SEGMENT_SUFFIX = ".log";
    private static final int DEFAULT_MAX_RECORDS = 512;

    private final Path dir;
    private final int maxRecords;
    private int segmentIndex;
    private int recordsInSegment;
    private long nextSeq;

    private CdcLog(Path dir, int maxRecords) throws IOException {
        this.dir = dir;
        this.maxRecords = maxRecords;
        Files.createDirectories(dir);
        this.segmentIndex = lastSegmentIndex();
        this.recordsInSegment = segmentIndex == 0 ? 0
                : readSegmentCount(segmentPath(segmentIndex));
        this.nextSeq = readAll().stream().mapToLong(ChangeEvent::seq)
                .max().orElse(-1) + 1;
    }

    public static CdcLog open(Path dir) throws IOException {
        return new CdcLog(dir, DEFAULT_MAX_RECORDS);
    }

    public static CdcLog open(Path dir, int maxRecords) throws IOException {
        return new CdcLog(dir, maxRecords);
    }

    public synchronized long append(ChangeEvent event) throws IOException {
        if (event.seq() != nextSeq) {
            throw new IllegalArgumentException(
                    "out-of-order seq: expected " + nextSeq
                            + " got " + event.seq());
        }
        if (recordsInSegment >= maxRecords) {
            rotate();
        }
        byte[] payload = encode(event);
        try (OutputStream raw = Files.newOutputStream(
                segmentPath(segmentIndex), java.nio.file.StandardOpenOption
                        .CREATE, java.nio.file.StandardOpenOption.APPEND);
             DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(raw))) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(payload.length);
            out.write(payload);
            out.writeInt(crc32(payload));
            out.flush();
        }
        recordsInSegment++;
        nextSeq++;
        return event.seq();
    }

    public synchronized List<ChangeEvent> readAll() throws IOException {
        List<ChangeEvent> events = new ArrayList<>();
        for (int i = 1; i <= Math.max(1, segmentIndex); i++) {
            Path path = segmentPath(i);
            if (Files.exists(path)) {
                readSegment(path, events);
            }
        }
        return events;
    }

    public long watermark() {
        return nextSeq - 1;
    }

    private void rotate() {
        segmentIndex++;
        recordsInSegment = 0;
    }

    private int lastSegmentIndex() throws IOException {
        int last = 1;
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith(SEGMENT_PREFIX)
                        && name.endsWith(SEGMENT_SUFFIX)) {
                    try {
                        last = Math.max(last, Integer.parseInt(
                                name.substring(SEGMENT_PREFIX.length(),
                                        name.length()
                                                - SEGMENT_SUFFIX.length())));
                    } catch (NumberFormatException ignored) {
                        // 非日志文件
                    }
                }
            }
        }
        return last;
    }

    private int readSegmentCount(Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        List<ChangeEvent> events = new ArrayList<>();
        readSegment(path, events);
        return events.size();
    }

    private Path segmentPath(int index) {
        return dir.resolve(String.format("%s%06d%s", SEGMENT_PREFIX,
                index, SEGMENT_SUFFIX));
    }

    private static void readSegment(Path path, List<ChangeEvent> target)
            throws IOException {
        try (InputStream raw = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(
                     new BufferedInputStream(raw))) {
            while (true) {
                int magic;
                try {
                    magic = in.readInt();
                } catch (EOFException e) {
                    return;
                }
                if (magic != MAGIC) {
                    return; // 尾部损坏容忍
                }
                in.readByte();
                int length = in.readInt();
                byte[] payload = new byte[length];
                try {
                    in.readFully(payload);
                } catch (EOFException e) {
                    return;
                }
                int expected = in.readInt();
                if (expected != crc32(payload)) {
                    return;
                }
                target.add(decode(payload));
            }
        }
    }

    private static byte[] encode(ChangeEvent event) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(event.seq());
            out.writeByte(event.type().ordinal());
            writeBytes(out, event.key());
            writeNullable(out, event.value());
            out.writeBoolean(event.deleted());
            out.writeUTF(event.txnId() == null ? "" : event.txnId());
            out.writeUTF(event.regionId() == null ? "" : event.regionId());
            out.writeLong(event.timestamp());
            out.flush();
        }
        return bytes.toByteArray();
    }

    private static ChangeEvent decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            long seq = in.readLong();
            ChangeEvent.EventType type = ChangeEvent.EventType
                    .values()[in.readUnsignedByte()];
            byte[] key = readBytes(in);
            byte[] value = readNullable(in);
            boolean deleted = in.readBoolean();
            String txnId = in.readUTF();
            String regionId = in.readUTF();
            long timestamp = in.readLong();
            return new ChangeEvent(seq, type, key, value, deleted,
                    txnId.isEmpty() ? null : txnId,
                    regionId.isEmpty() ? null : regionId, timestamp);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes)
            throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeNullable(DataOutputStream out, byte[] bytes)
            throws IOException {
        if (bytes == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static byte[] readNullable(DataInputStream in)
            throws IOException {
        int length = in.readInt();
        if (length == -1) {
            return null;
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static int crc32(byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(payload);
        return (int) crc.getValue();
    }
}
