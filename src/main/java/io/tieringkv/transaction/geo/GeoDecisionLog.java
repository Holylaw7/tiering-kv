package io.tieringkv.transaction.geo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/** 地域决策日志（ADR-0109）：追加 + CRC，尾部损坏容忍。 */
public final class GeoDecisionLog {

    private static final int MAGIC = 0x47444543; // 'GDEC'

    private final Path file;

    private GeoDecisionLog(Path file) {
        this.file = file;
    }

    public static GeoDecisionLog open(Path dir) throws IOException {
        Files.createDirectories(dir);
        return new GeoDecisionLog(dir.resolve("geo-decisions.log"));
    }

    public synchronized void append(GeoDecision decision)
            throws IOException {
        byte[] payload = encode(decision);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND)))) {
            out.writeInt(MAGIC);
            out.writeInt(payload.length);
            out.write(payload);
            CRC32C crc = new CRC32C();
            crc.update(payload);
            out.writeInt((int) crc.getValue());
            out.flush();
        }
    }

    public synchronized List<GeoDecision> readAll() throws IOException {
        List<GeoDecision> decisions = new ArrayList<>();
        if (!Files.exists(file)) {
            return decisions;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            while (true) {
                int magic;
                try {
                    magic = in.readInt();
                } catch (EOFException e) {
                    return decisions;
                }
                if (magic != MAGIC) {
                    return decisions; // 尾部损坏容忍
                }
                int length = in.readInt();
                byte[] payload = new byte[length];
                try {
                    in.readFully(payload);
                } catch (EOFException e) {
                    return decisions;
                }
                CRC32C crc = new CRC32C();
                crc.update(payload);
                int storedCrc;
                try {
                    storedCrc = in.readInt();
                } catch (EOFException e) {
                    return decisions;
                }
                if (storedCrc != (int) crc.getValue()) {
                    return decisions;
                }
                decisions.add(decode(payload));
            }
        }
    }

    private static byte[] encode(GeoDecision decision) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(decision.txnId());
            out.writeByte(decision.decision().ordinal());
            out.writeLong(decision.commitTS());
            out.flush();
        }
        return bytes.toByteArray();
    }

    private static GeoDecision decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            return new GeoDecision(in.readUTF(),
                    GeoDecision.Decision.values()[in.readUnsignedByte()],
                    in.readLong());
        }
    }
}
