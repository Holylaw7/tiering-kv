package io.tieringkv.backup.pitr;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/** PITR 变更日志（ADR-0104）：分段追加 + CRC32C，尾部损坏容忍。 */
public final class PitrWriteLog {

    private static final int MAGIC = 0x5450574C; // 'TPWL'
    private static final byte VERSION = 1;
    private static final String SEGMENT_PREFIX = "pitr-";
    private static final String SEGMENT_SUFFIX = ".log";
    private static final int DEFAULT_MAX_RECORDS = 512;

    private final Path dir;
    private final int maxRecords;
    private int segmentIndex;
    private int recordsInSegment;
    private long nextSeq;

    private PitrWriteLog(Path dir, int maxRecords) throws IOException {
        this.dir = dir;
        this.maxRecords = maxRecords;
        Files.createDirectories(dir);
        this.segmentIndex = lastSegmentIndex();
        this.recordsInSegment = segmentIndex == 0 ? 0
                : countRecords(segmentPath(segmentIndex));
        this.nextSeq = readAll().stream().mapToLong(PitrRecord::seq)
                .max().orElse(-1) + 1;
    }

    public static PitrWriteLog open(Path dir) throws IOException {
        return new PitrWriteLog(dir, DEFAULT_MAX_RECORDS);
    }

    public static PitrWriteLog open(Path dir, int maxRecords)
            throws IOException {
        return new PitrWriteLog(dir, maxRecords);
    }

    public synchronized long append(PitrRecord record) throws IOException {
        if (record.seq() != nextSeq) {
            throw new IllegalArgumentException(
                    "out-of-order seq: expected " + nextSeq
                            + " got " + record.seq());
        }
        if (recordsInSegment >= maxRecords) {
            rotate();
        }
        byte[] payload = encodePayload(record);
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
        return record.seq();
    }

    public synchronized List<PitrRecord> readAll() throws IOException {
        List<PitrRecord> records = new ArrayList<>();
        for (int i = 1; i <= Math.max(1, segmentIndex); i++) {
            Path path = segmentPath(i);
            if (!Files.exists(path)) {
                continue;
            }
            readSegment(path, records, true);
        }
        return records;
    }

    public long watermark() {
        return nextSeq - 1;
    }

    public Path dir() {
        return dir;
    }

    public static List<PitrRecord> read(Path segment) throws IOException {
        List<PitrRecord> records = new ArrayList<>();
        readSegment(segment, records, false);
        return records;
    }

    private void rotate() throws IOException {
        segmentIndex++;
        recordsInSegment = 0;
    }

    private int lastSegmentIndex() throws IOException {
        if (!Files.exists(dir)) {
            return 1;
        }
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

    private int countRecords(Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        List<PitrRecord> records = new ArrayList<>();
        readSegment(path, records, true);
        return records.size();
    }

    private Path segmentPath(int index) {
        return dir.resolve(String.format("%s%06d%s", SEGMENT_PREFIX,
                index, SEGMENT_SUFFIX));
    }

    private static void readSegment(Path path, List<PitrRecord> target,
                                    boolean tolerateTail)
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
                    if (tolerateTail) {
                        return;
                    }
                    throw new IOException("bad magic in " + path);
                }
                in.readByte(); // version
                int length = in.readInt();
                byte[] payload = new byte[length];
                try {
                    in.readFully(payload);
                } catch (EOFException e) {
                    if (tolerateTail) {
                        return;
                    }
                    throw e;
                }
                int expectedCrc = in.readInt();
                if (expectedCrc != crc32(payload)) {
                    if (tolerateTail) {
                        return;
                    }
                    throw new IOException("crc mismatch in " + path);
                }
                target.add(decodePayload(payload));
            }
        }
    }

    private static byte[] encodePayload(PitrRecord record)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(record.seq());
            out.writeLong(record.startTS());
            out.writeLong(record.commitTS());
            writeBytes(out, record.key());
            writeNullable(out, record.value());
            out.writeBoolean(record.deleted());
            out.writeUTF(record.txnId() == null ? "" : record.txnId());
            out.writeUTF(record.regionId() == null ? "" : record.regionId());
            out.flush();
        }
        return bytes.toByteArray();
    }

    private static PitrRecord decodePayload(byte[] payload)
            throws IOException {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            long seq = in.readLong();
            long startTS = in.readLong();
            long commitTS = in.readLong();
            byte[] key = readBytes(in);
            byte[] value = readNullable(in);
            boolean deleted = in.readBoolean();
            String txnId = in.readUTF();
            String regionId = in.readUTF();
            return new PitrRecord(seq, startTS, commitTS, key, value,
                    deleted, txnId.isEmpty() ? null : txnId,
                    regionId.isEmpty() ? null : regionId);
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
