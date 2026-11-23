package io.tieringkv.cluster.raft;

import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RaftLog 持久化（ADR-0039）：追加/恢复/CRC 截断/快照压缩/状态持久。 */
class RaftLogPersistenceTest {

    @TempDir
    Path dir;

    @Test
    void appendAndReadBack() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            log.append(entry(1, 0, "a"));
            log.append(entry(1, 1, "b"));
            assertThat(log.size()).isEqualTo(2);
            assertThat(log.entryAt(0).command()).isEqualTo("a".getBytes(StandardCharsets.UTF_8));
            assertThat(log.entryAt(1).term()).isEqualTo(1);
            assertThat(log.lastIndex()).isEqualTo(1);
            assertThat(log.lastTerm()).isEqualTo(1);
        }
    }

    @Test
    void restartRecoveryPreservesEntries() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            for (int i = 0; i < 100; i++) {
                log.append(entry(2, i, "v" + i));
            }
        }
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            assertThat(log.size()).isEqualTo(100);
            assertThat(log.firstIndex()).isZero();
            assertThat(log.lastIndex()).isEqualTo(99);
            assertThat(log.entryAt(42).command()).isEqualTo("v42".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void crcFailureTruncatesCorruptTail() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            log.append(entry(1, 0, "a"));
            log.append(entry(1, 1, "b"));
            log.append(entry(1, 2, "c"));
        }
        corruptBytes(segmentFile(0), 32, (byte) 0x55); // 破坏第二条记录
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            assertThat(log.size()).isEqualTo(1);
            assertThat(log.entryAt(0).command()).isEqualTo("a".getBytes(StandardCharsets.UTF_8));
            assertThat(log.lastIndex()).isZero();
        }
    }

    @Test
    void corruptedMidSegmentStopsAtValidPrefix() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            for (int i = 0; i < 5; i++) {
                log.append(entry(1, i, "x" + i));
            }
        }
        corruptBytes(segmentFile(0), 65, (byte) 0x22); // 第三条记录头部
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            assertThat(log.size()).isEqualTo(2);
            assertThat(log.lastIndex()).isEqualTo(1);
        }
    }

    @Test
    void corruptEarlySegmentDropsLaterSegments() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC, 1 << 20, 3)) {
            for (int i = 0; i < 8; i++) {
                log.append(entry(1, i, "s" + i));
            }
        }
        assertThat(Files.list(dir).filter(p -> p.getFileName().toString().startsWith("segment-"))
                .count()).isGreaterThan(1);
        corruptBytes(segmentFile(0), 1, (byte) 0x11);
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            // 首段损坏 → 后续段因连续性依赖被删除，日志为空
            assertThat(log.size()).isZero();
            assertThat(log.lastIndex()).isEqualTo(-1);
        }
    }

    @Test
    void truncateFromKeepsPrefix() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            for (int i = 0; i < 10; i++) {
                log.append(entry(1, i, "t" + i));
            }
            log.truncateFrom(6);
            assertThat(log.size()).isEqualTo(6);
            assertThat(log.lastIndex()).isEqualTo(5);
            assertThat(log.entryAt(5).command()).isEqualTo("t5".getBytes(StandardCharsets.UTF_8));
        }
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            assertThat(log.size()).isEqualTo(6);
        }
    }

    @Test
    void truncateFromMiddleSegment() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC, 1 << 20, 3)) {
            for (int i = 0; i < 9; i++) {
                log.append(entry(1, i, "m" + i));
            }
            log.truncateFrom(4);
            assertThat(log.size()).isEqualTo(4);
            assertThat(log.lastIndex()).isEqualTo(3);
        }
    }

    @Test
    void truncateFromAllClearsLog() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            for (int i = 0; i < 5; i++) {
                log.append(entry(1, i, "c" + i));
            }
            log.truncateFrom(0);
            assertThat(log.size()).isZero();
            assertThat(log.firstIndex()).isZero();
            assertThat(log.lastIndex()).isEqualTo(-1);
        }
    }

    @Test
    void installSnapshotCompactsLog() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            for (int i = 0; i < 100; i++) {
                log.append(entry(3, i, "snap" + i));
            }
            log.installSnapshot(90);
            assertThat(log.firstIndex()).isEqualTo(91);
            assertThat(log.lastIndex()).isEqualTo(99);
            assertThat(log.size()).isEqualTo(9);
            assertThatThrownBy(() -> log.entryAt(90))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            assertThat(log.firstIndex()).isEqualTo(91);
            assertThat(log.lastIndex()).isEqualTo(99);
        }
    }

    @Test
    void rotationCreatesMultipleSegments() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC, 1 << 20, 4)) {
            for (int i = 0; i < 10; i++) {
                log.append(entry(1, i, "r" + i));
            }
        }
        long segmentCount;
        try (var stream = Files.list(dir)) {
            segmentCount = stream.filter(p -> p.getFileName().toString().startsWith("segment-"))
                    .count();
        }
        assertThat(segmentCount).isGreaterThanOrEqualTo(3);
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            assertThat(log.size()).isEqualTo(10);
            assertThat(log.entryAt(9).command()).isEqualTo("r9".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void outOfOrderAppendRejected() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            log.append(entry(1, 0, "a"));
            assertThatThrownBy(() -> log.append(entry(1, 2, "c")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void syncDurabilityWritesSurviveReopen() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            log.append(entry(5, 0, "durable"));
        }
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.ASYNC)) {
            assertThat(log.entryAt(0).command())
                    .isEqualTo("durable".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void asyncDurabilityAppendsAndSyncFlushes() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.ASYNC)) {
            for (int i = 0; i < 20; i++) {
                log.append(entry(1, i, "async" + i));
            }
            log.sync();
        }
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.NONE)) {
            assertThat(log.size()).isEqualTo(20);
        }
    }

    @Test
    void noneDurabilityStillAppends() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.NONE)) {
            log.append(entry(1, 0, "none"));
            assertThat(log.size()).isEqualTo(1);
        }
    }

    @Test
    void baseIndexPersistedAcrossRestart() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            for (int i = 0; i < 10; i++) {
                log.append(entry(1, i, "b" + i));
            }
            log.installSnapshot(9);
        }
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            assertThat(log.firstIndex()).isEqualTo(10);
            // 快照包含到 index 9：日志为空但 lastIndex 语义回退到 9
            assertThat(log.lastIndex()).isEqualTo(9);
            log.append(entry(2, 10, "resume"));
            assertThat(log.entryAt(10).term()).isEqualTo(2);
        }
    }

    @Test
    void entriesFromPartialRange() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            for (int i = 0; i < 10; i++) {
                log.append(entry(1, i, "e" + i));
            }
            List<LogEntry> entries = log.entriesFrom(7);
            assertThat(entries).hasSize(3);
            assertThat(entries.get(0).index()).isEqualTo(7);
            assertThat(entries.get(2).index()).isEqualTo(9);
            assertThat(log.entriesFrom(99)).isEmpty();
        }
    }

    @Test
    void termAtReadsEntryTerm() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            log.append(entry(4, 0, "a"));
            log.append(entry(6, 1, "b"));
            assertThat(log.termAt(0)).isEqualTo(4);
            assertThat(log.termAt(1)).isEqualTo(6);
        }
    }

    @Test
    void recoveryTruncatesAndReportsBytes() throws IOException {
        try (FileRaftLog log = FileRaftLog.open(dir, Durability.SYNC)) {
            for (int i = 0; i < 4; i++) {
                log.append(entry(1, i, "d" + i));
            }
        }
        corruptBytes(segmentFile(0), 90, (byte) 0x33);
        io.tieringkv.cluster.raft.log.RaftLogRecovery.Result result =
                io.tieringkv.cluster.raft.log.RaftLogRecovery.recover(dir);
        assertThat(result.truncatedBytes()).isGreaterThan(0);
        result.segments().forEach(segment -> {
            try {
                segment.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void termAndVotedForPersistAcrossRestart() throws IOException {
        try (RaftPersistentState state = RaftPersistentState.open(dir)) {
            state.persist(7, "n2", 5);
        }
        try (RaftPersistentState state = RaftPersistentState.open(dir)) {
            assertThat(state.term()).isEqualTo(7);
            assertThat(state.votedFor()).isEqualTo("n2");
            assertThat(state.commitIndex()).isEqualTo(5);
        }
    }

    @Test
    void commitIndexPersistedAcrossRestart() throws IOException {
        try (RaftPersistentState state = RaftPersistentState.open(dir)) {
            state.persist(3, null, 42);
        }
        try (RaftPersistentState state = RaftPersistentState.open(dir)) {
            assertThat(state.commitIndex()).isEqualTo(42);
            assertThat(state.votedFor()).isNull();
        }
    }

    @Test
    void corruptedStateFileFallsBackToDefaults() throws IOException {
        try (RaftPersistentState state = RaftPersistentState.open(dir)) {
            state.persist(9, "n1", 8);
        }
        byte[] bytes = Files.readAllBytes(dir.resolve("raft.state"));
        bytes[bytes.length - 1] ^= 0x01; // 破坏 CRC
        Files.write(dir.resolve("raft.state"), bytes);
        try (RaftPersistentState state = RaftPersistentState.open(dir)) {
            assertThat(state.term()).isZero();
            assertThat(state.votedFor()).isNull();
            assertThat(state.commitIndex()).isEqualTo(-1);
        }
    }

    private static LogEntry entry(long term, long index, String command) {
        return new LogEntry(term, index, command.getBytes(StandardCharsets.UTF_8));
    }

    private Path segmentFile(long segmentIndex) {
        return dir.resolve(String.format("segment-%020d.log", segmentIndex));
    }

    private static void corruptBytes(Path file, int offset, byte value) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[offset] = value;
        Files.write(file, bytes);
    }
}
