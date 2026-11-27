package io.tieringkv.storage.wal;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单个 WAL 段文件（ADR-0014 + v6.5.0 Phase 1/3 优化）：
 * <ul>
 *   <li>移除 64 KB {@code BufferedOutputStream} 堆内双拷贝，直接基于 {@link FileChannel} 写堆外 {@link ByteBuffer}；</li>
 *   <li>支持段预分配（消除追加写频繁变更 {@code inode.i_size}）与 {@code fdatasync}（{@code force(false)}）；</li>
 *   <li>支持纯 Java 21 {@code ExtendedOpenOption.DIRECT}（{@code O_DIRECT} 4KB 对齐尾页覆写），
 *       文件系统不支持时自动无感回退标准 NIO。</li>
 * </ul>
 */
public final class LogSegment implements AutoCloseable {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(LogSegment.class);

    private static final int DEFAULT_BLOCK_SIZE = 4096;
    private static final int PREALLOC_CHUNK_BYTES = 1024 * 1024;
    private static final long PREALLOC_STEP_BYTES = 4L * 1024 * 1024;
    private static final int DIRECT_STAGING_CAPACITY = 256 * 1024;
    private static final ByteBuffer ZERO_CHUNK = ByteBuffer.allocateDirect(PREALLOC_CHUNK_BYTES).asReadOnlyBuffer();
    private static final OpenOption DIRECT_OPEN_OPTION = resolveDirectOpenOption();
    private static volatile boolean directFallbackLogged;

    private final long sequence;
    private final Path path;
    private final boolean directIo;
    private final boolean preallocated;
    private final int blockSize;
    private final ByteBuffer tailPage;
    private final ByteBuffer directStaging;
    private FileChannel channel;
    private long logicalSize;
    private long allocatedSize;
    private boolean metadataSynced;

    private LogSegment(
            long sequence,
            Path path,
            FileChannel channel,
            long logicalSize,
            long allocatedSize,
            boolean directIo,
            boolean preallocated,
            int blockSize,
            ByteBuffer tailPage,
            ByteBuffer directStaging,
            boolean metadataSynced) {
        this.sequence = sequence;
        this.path = path;
        this.channel = channel;
        this.logicalSize = logicalSize;
        this.allocatedSize = allocatedSize;
        this.directIo = directIo;
        this.preallocated = preallocated;
        this.blockSize = blockSize;
        this.tailPage = tailPage;
        this.directStaging = directStaging;
        this.metadataSynced = metadataSynced;
    }

    public static LogSegment openOrCreate(Path directory, long sequence) throws IOException {
        return openOrCreate(directory, sequence, WALConfig.IoMode.NIO, 0L);
    }

    public static LogSegment openOrCreate(
            Path directory,
            long sequence,
            WALConfig.IoMode ioMode,
            long preallocateBytes) throws IOException {
        Path path = directory.resolve(String.format("%06d.log", sequence));
        boolean existed = Files.exists(path);
        long physicalSize = existed ? Files.size(path) : 0L;

        int blockSize = probeBlockSize(directory);
        // AUTO 默认采用 NIO FileChannel + 预分配 + fdatasync（实测 ALWAYS 下较 O_DIRECT 快 1.8x，
        // EVERY_SEC 吞吐最优）；显式配置 ioMode=DIRECT 时启用 Linux O_DIRECT 4KB 对齐写（最优长尾毛刺）
        boolean wantDirect = ioMode == WALConfig.IoMode.DIRECT && DIRECT_OPEN_OPTION != null;

        // 先计算既有文件的逻辑有效末尾（若曾预分配或 O_DIRECT 写过零尾，避免把零填充算作有效偏移）
        long logicalSize = physicalSize > 0 ? findLogicalEnd(path, physicalSize) : 0L;

        boolean didPreallocate = false;
        if (preallocateBytes > 0) {
            long initialPrealloc = Math.min(preallocateBytes, PREALLOC_STEP_BYTES);
            long targetBytes = alignUp(initialPrealloc, blockSize);
            if (physicalSize < targetBytes) {
                preallocateFile(path, physicalSize, targetBytes);
                physicalSize = targetBytes;
            }
            didPreallocate = true;
        }

        FileChannel ch = null;
        boolean effectiveDirect = false;
        ByteBuffer tailPage = null;
        ByteBuffer staging = null;

        if (wantDirect && DIRECT_OPEN_OPTION != null) {
            try {
                ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, DIRECT_OPEN_OPTION);
                tailPage = ByteBuffer.allocateDirect(blockSize * 2).alignedSlice(blockSize);
                staging = ByteBuffer.allocateDirect(DIRECT_STAGING_CAPACITY + blockSize * 2).alignedSlice(blockSize);
                // 若文件已有部分尾页内容，先通过普通读加载尾页以保证续写无缝拼接
                int tailLen = (int) (logicalSize & (blockSize - 1));
                if (tailLen > 0) {
                    loadTailPage(path, logicalSize - tailLen, tailLen, tailPage);
                } else if (logicalSize == 0) {
                    // 一次幂等 4KB 零块探针写：立即识别仅在 write() 时才报 EINVAL 的虚拟挂载（如 FUSE/OverlayFS）
                    ByteBuffer probe = staging.duplicate();
                    probe.position(0).limit(blockSize);
                    for (int i = 0; i < blockSize; i++) {
                        probe.put(i, (byte) 0);
                    }
                    ch.write(probe, 0L);
                }
                effectiveDirect = true;
            } catch (UnsupportedOperationException | IOException e) {
                if (ch != null) {
                    try {
                        ch.close();
                    } catch (IOException ignored) {
                    }
                    ch = null;
                }
                if (!directFallbackLogged) {
                    directFallbackLogged = true;
                    LOG.warn("WAL O_DIRECT unavailable on {} ({}), falling back to standard NIO FileChannel",
                            directory, e.getMessage());
                }
            }
        }

        if (ch == null) {
            ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (!existed && !didPreallocate) {
                ch.force(true);
            }
        }
        boolean metaSynced = existed || didPreallocate || !effectiveDirect;
        return new LogSegment(
                sequence, path, ch, logicalSize, physicalSize, effectiveDirect, didPreallocate,
                blockSize, tailPage, staging, metaSynced);
    }

    public void append(byte[] record) throws IOException {
        append(ByteBuffer.wrap(record));
    }

    /** 零拷贝追加写（接受堆外 DirectByteBuffer 或堆内 ByteBuffer，写入 [position, limit) 全部字节）。 */
    public void append(ByteBuffer src) throws IOException {
        if (!src.hasRemaining()) {
            return;
        }
        if (preallocated && logicalSize + src.remaining() > allocatedSize) {
            long target = alignUp(Math.max(allocatedSize + PREALLOC_STEP_BYTES, logicalSize + src.remaining()),
                    blockSize);
            preallocateFile(path, allocatedSize, target);
            allocatedSize = target;
            metadataSynced = true;
        }
        if (directIo) {
            appendDirect(src);
            return;
        }
        long pos = logicalSize;
        while (src.hasRemaining()) {
            int written = channel.write(src, pos);
            if (written <= 0) {
                throw new IOException("WAL FileChannel.write returned " + written + " for " + path);
            }
            pos += written;
        }
        logicalSize = pos;
    }

    private void appendDirect(ByteBuffer src) throws IOException {
        int mask = blockSize - 1;
        while (src.hasRemaining()) {
            int tailLen = (int) (logicalSize & mask);
            long alignedBase = logicalSize - tailLen;
            int maxChunk = DIRECT_STAGING_CAPACITY - tailLen;
            int chunkLen = Math.min(src.remaining(), maxChunk);
            int totalValid = tailLen + chunkLen;
            int paddedLen = (totalValid + mask) & ~mask;

            directStaging.clear();
            if (tailLen > 0) {
                ByteBuffer prev = tailPage.duplicate();
                prev.position(0).limit(tailLen);
                directStaging.put(prev);
            }
            int savedLimit = src.limit();
            src.limit(src.position() + chunkLen);
            directStaging.put(src);
            src.limit(savedLimit);

            // 末尾零填充至 blockSize 整数倍，兼作崩溃恢复干净逻辑 EOF 哨兵（magic == 0）
            for (int i = totalValid; i < paddedLen; i++) {
                directStaging.put(i, (byte) 0);
            }
            directStaging.position(0).limit(paddedLen);

            long writePos = alignedBase;
            while (directStaging.hasRemaining()) {
                int written = channel.write(directStaging, writePos);
                if (written <= 0) {
                    throw new IOException("WAL O_DIRECT short write (0 bytes) for " + path);
                }
                writePos += written;
            }
            logicalSize += chunkLen;

            int newTailLen = totalValid & mask;
            if (newTailLen > 0) {
                int newTailStart = totalValid - newTailLen;
                ByteBuffer slice = directStaging.duplicate();
                slice.position(newTailStart).limit(totalValid);
                tailPage.clear();
                tailPage.put(slice);
            }
        }
    }

    public void flush() throws IOException {
        // FileChannel 直写无用户态 BufferedOutputStream 滞留，flush 为空操作
    }

    /** 持久化刷盘：段元数据已固化时走 fdatasync（force(false)），减少文件系统日志写放大。 */
    public void force() throws IOException {
        if (!metadataSynced) {
            channel.force(true);
            metadataSynced = true;
        } else {
            channel.force(false);
        }
    }

    /** 强制含元数据完整 fsync（段滚动/关闭前使用）。 */
    public void forceMetadata() throws IOException {
        channel.force(true);
        metadataSynced = true;
    }

    public long sequence() {
        return sequence;
    }

    public Path path() {
        return path;
    }

    /** 返回逻辑有效字节偏移（而非预分配物理文件长度）。 */
    public long size() {
        return logicalSize;
    }

    public boolean isDirectIo() {
        return directIo;
    }

    public boolean isPreallocated() {
        return preallocated;
    }

    public String ioBackendName() {
        return directIo ? "nio-direct" : "nio";
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    /**
     * 快速定位既有段文件的逻辑写入末尾：
     * 仅按记录头（38B）跳跃扫描，遇到全 0 预分配尾区或截断头即停止，不读取 value 负载。
     */
    static long findLogicalEnd(Path path, long physicalSize) throws IOException {
        if (physicalSize < WALRecord.HEADER_SIZE) {
            return physicalSize;
        }
        try (FileChannel readCh = FileChannel.open(path, StandardOpenOption.READ)) {
            // 快路径：检查文件末尾 8 字节；普通未预分配段末尾是 CRC32C（极少全 0），
            // 仅当末尾疑似 0 填充或段可能是预分配文件时才顺序跳扫头部
            ByteBuffer tailCheck = ByteBuffer.allocate(8);
            readCh.read(tailCheck, physicalSize - 8);
            tailCheck.flip();
            if (tailCheck.getLong() != 0L) {
                return physicalSize;
            }
            ByteBuffer header = ByteBuffer.allocate(WALRecord.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            long pos = 0L;
            while (pos + WALRecord.HEADER_SIZE <= physicalSize) {
                header.clear();
                int n = readFully(readCh, header, pos);
                if (n < WALRecord.HEADER_SIZE) {
                    return pos;
                }
                header.flip();
                int magic = header.getInt(0);
                if (magic == 0) {
                    return pos;
                }
                if (magic != WALRecord.MAGIC) {
                    return pos;
                }
                int keyLen = header.getInt(14);
                int valLen = header.getInt(18);
                if (keyLen < 0 || valLen < 0) {
                    return pos;
                }
                long nextPos = pos + WALRecord.HEADER_SIZE + (long) keyLen + (long) valLen + WALRecord.CHECKSUM_SIZE;
                if (nextPos > physicalSize) {
                    return pos;
                }
                pos = nextPos;
            }
            return pos;
        }
    }

    private static int readFully(FileChannel ch, ByteBuffer dst, long pos) throws IOException {
        int total = 0;
        while (dst.hasRemaining()) {
            int n = ch.read(dst, pos + total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static void loadTailPage(Path path, long alignedBase, int tailLen, ByteBuffer tailPage)
            throws IOException {
        try (FileChannel readCh = FileChannel.open(path, StandardOpenOption.READ)) {
            tailPage.clear();
            tailPage.limit(tailLen);
            int read = readFully(readCh, tailPage, alignedBase);
            if (read < tailLen) {
                throw new IOException("failed to load O_DIRECT tail page for " + path);
            }
            tailPage.clear();
        }
    }

    private static void preallocateFile(Path path, long currentSize, long targetBytes) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            long pos = currentSize;
            while (pos < targetBytes) {
                int chunk = (int) Math.min(PREALLOC_CHUNK_BYTES, targetBytes - pos);
                ByteBuffer zeros = ZERO_CHUNK.duplicate();
                zeros.position(0).limit(chunk);
                while (zeros.hasRemaining()) {
                    pos += ch.write(zeros, pos);
                }
            }
            ch.force(true);
        }
    }

    private static long alignUp(long value, int alignment) {
        long mask = alignment - 1L;
        return (value + mask) & ~mask;
    }

    private static int probeBlockSize(Path directory) {
        try {
            FileStore store = Files.getFileStore(directory);
            long bs = store.getBlockSize();
            if (bs >= 512 && bs <= 65536 && (bs & (bs - 1)) == 0) {
                return (int) bs;
            }
        } catch (IOException | UnsupportedOperationException ignored) {
        }
        return DEFAULT_BLOCK_SIZE;
    }

    private static OpenOption resolveDirectOpenOption() {
        // 仅在 Linux 上启用 ExtendedOpenOption.DIRECT；Windows FILE_FLAG_NO_BUFFERING
        // 对只读/并发读打开有额外扇区约束，开发机默认走标准 NIO
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("linux")) {
            return null;
        }
        try {
            Class<?> cls = Class.forName("com.sun.nio.file.ExtendedOpenOption");
            Field field = cls.getField("DIRECT");
            Object val = field.get(null);
            return val instanceof OpenOption opt ? opt : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
