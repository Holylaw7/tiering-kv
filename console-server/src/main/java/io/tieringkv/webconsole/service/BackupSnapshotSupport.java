package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.support.Text;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.zip.GZIPInputStream;

/** 备份/恢复/校验共享的无状态 I/O 与格式辅助工具（Wave 3B M2 拆分）。 */
final class BackupSnapshotSupport {

    static final long PROGRESS_FLUSH_KEYS = 500L;
    static final long PROGRESS_FLUSH_MS = 1_000L;
    static final int PIPELINE_BATCH_SIZE = 100;

    private BackupSnapshotSupport() {
    }

    /** 自动识别 GZIP 魔数（0x1F 0x8B），兼容明文 .jsonl 与压缩 .jsonl.gz。 */
    static BufferedReader openSnapshotReader(BackupStorageBackend storageBackend, String fileName)
            throws IOException {
        InputStream raw = new BufferedInputStream(storageBackend.openInput(fileName));
        PushbackInputStream pb = new PushbackInputStream(raw, 2);
        byte[] header = new byte[2];
        int read = pb.read(header);
        if (read > 0) {
            pb.unread(header, 0, read);
        }
        InputStream decoded = isGzipHeader(header, read) ? new GZIPInputStream(pb, 8192) : pb;
        return new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8));
    }

    static boolean isGzipHeader(byte[] header, int read) {
        return read == 2 && (header[0] & 0xff) == 0x1f && (header[1] & 0xff) == 0x8b;
    }

    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256 算法实现", ex);
        }
    }

    static String instanceName(InstanceService instanceService, Long instanceId) {
        try {
            return instanceService.require(instanceId).getName();
        } catch (RuntimeException ex) {
            return "instance#" + instanceId;
        }
    }

    static boolean isFresh(LocalDateTime time, long minutes) {
        return time != null && time.isAfter(LocalDateTime.now().minusMinutes(minutes));
    }

    static String text(RespReply reply) {
        return reply instanceof RespReply.Bulk bulk ? Text.decode(bulk.value()) : null;
    }

    static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        long bytesRead() {
            return count;
        }
    }

    static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // 保留外层 DigestInputStream 供尾部排空计算完整文件 SHA-256
        }
    }
}
