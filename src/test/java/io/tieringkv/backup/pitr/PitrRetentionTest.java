package io.tieringkv.backup.pitr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PITR 保留策略（ADR-0111）：安全水位删除与恢复保护。 */
class PitrRetentionTest {

    @TempDir
    Path dir;

    @Test
    void policyRejectsZeroSegments() {
        assertThatThrownBy(() -> new RetentionPolicy(0, 1_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = {1, 3, 10})
    void policyValidates(int maxSegments) {
        RetentionPolicy policy = new RetentionPolicy(maxSegments,
                60_000, 0);
        assertThat(policy.shouldRetain(1, 0, 0)).isTrue();
    }

    @Test
    void safeWatermarkNeverDeleted() throws Exception {
        Path archiveDir = dir.resolve("safe");
        PitrWriteLog log = PitrWriteLog.open(archiveDir, 5);
        for (int i = 0; i < 30; i++) {
            log.append(record(i));
        }
        RetentionPolicy policy = new RetentionPolicy(1, 0,
                Long.MAX_VALUE);
        ArchiveLifecycleManager manager = new ArchiveLifecycleManager(
                archiveDir, policy);
        assertThat(manager.cleanup()).isEmpty();
        assertThat(manager.segmentCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void oldSegmentsDeletedBeyondPolicy() throws Exception {
        Path archiveDir = dir.resolve("clean");
        PitrWriteLog log = PitrWriteLog.open(archiveDir, 5);
        for (int i = 0; i < 30; i++) {
            log.append(record(i));
        }
        RetentionPolicy policy = new RetentionPolicy(2, 0, -1);
        ArchiveLifecycleManager manager = new ArchiveLifecycleManager(
                archiveDir, policy);
        manager.cleanup();
        assertThat(manager.segmentCount()).isLessThanOrEqualTo(2);
    }

    @Test
    void restoreStillWorksAfterCleanup() throws Exception {
        Path archiveDir = dir.resolve("restore-after");
        PitrWriteLog log = PitrWriteLog.open(archiveDir, 5);
        for (int i = 0; i < 20; i++) {
            log.append(record(i));
        }
        ArchiveLifecycleManager manager = new ArchiveLifecycleManager(
                archiveDir, new RetentionPolicy(2, 0, -1));
        manager.cleanup();
        assertThat(log.readAll().size()).isGreaterThan(0);
    }

    @Test
    void missingArchiveDirCleanupEmpty() throws Exception {
        ArchiveLifecycleManager manager = new ArchiveLifecycleManager(
                dir.resolve("missing"), new RetentionPolicy(1, 0, -1));
        assertThat(manager.cleanup()).isEmpty();
        assertThat(manager.segmentCount()).isZero();
    }

    @ParameterizedTest(name = "segments {0}")
    @ValueSource(ints = {2, 5, 10})
    void parameterizedSegmentCleanup(int maxSegments) throws Exception {
        Path archiveDir = dir.resolve("param-" + maxSegments);
        PitrWriteLog log = PitrWriteLog.open(archiveDir, 3);
        for (int i = 0; i < 30; i++) {
            log.append(record(i));
        }
        ArchiveLifecycleManager manager = new ArchiveLifecycleManager(
                archiveDir, new RetentionPolicy(maxSegments, 0, -1));
        manager.cleanup();
        assertThat(manager.segmentCount())
                .isLessThanOrEqualTo(maxSegments);
    }

    @Test
    void freshSegmentsKeptByAge() throws Exception {
        Path archiveDir = dir.resolve("age");
        PitrWriteLog log = PitrWriteLog.open(archiveDir, 3);
        for (int i = 0; i < 9; i++) {
            log.append(record(i));
        }
        ArchiveLifecycleManager manager = new ArchiveLifecycleManager(
                archiveDir, new RetentionPolicy(1, 3_600_000, -1));
        assertThat(manager.cleanup()).isEmpty();
    }

    @Test
    void cleanupReportsRemovedNames() throws Exception {
        Path archiveDir = dir.resolve("report");
        PitrWriteLog log = PitrWriteLog.open(archiveDir, 2);
        for (int i = 0; i < 12; i++) {
            log.append(record(i));
        }
        ArchiveLifecycleManager manager = new ArchiveLifecycleManager(
                archiveDir, new RetentionPolicy(1, 0, -1));
        java.util.List<String> removed = manager.cleanup();
        assertThat(removed).isNotEmpty();
        assertThat(removed).allMatch(name -> name.startsWith("pitr-"));
    }

    private static PitrRecord record(long seq) {
        return new PitrRecord(seq, seq, seq * 10,
                ("k" + seq).getBytes(), ("v" + seq).getBytes(), false,
                "t" + seq, "r1");
    }
}
