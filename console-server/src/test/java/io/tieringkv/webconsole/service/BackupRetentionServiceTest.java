package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G0-2 / ADR-0367：备份保留策略与安全删除。
 *
 * <p>每个用例对应一条生产不变量，而不是为了覆盖率凑数。
 */
class BackupRetentionServiceTest {

    @TempDir
    Path dir;

    private final Map<Long, BackupRecord> store = new LinkedHashMap<>();
    private BackupRecordRepository repository;
    private BackupRetentionService service;
    private ConsoleProperties properties;
    private DbInstance instance;
    private long clock;

    @BeforeEach
    void setUp() {
        repository = mock(BackupRecordRepository.class);
        DbInstanceRepository instances = mock(DbInstanceRepository.class);
        properties = new ConsoleProperties();
        properties.getBackup().setDir(dir.toString());
        properties.getBackup().getRetention().setDefaultCount(2);
        instance = new DbInstance();
        instance.setId(1L);
        instance.setName("local");
        when(instances.findAll()).thenReturn(List.of(instance));

        when(repository.existsById(anyLong())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
        when(repository.findById(anyLong())).thenAnswer(inv -> java.util.Optional.ofNullable(store.get(inv.getArgument(0))));
        when(repository.findByInstanceId(anyLong())).thenAnswer(inv -> new ArrayList<>(store.values()));
        when(repository.findByStatusAndCreatedAtBefore(any(), any())).thenAnswer(inv -> {
            BackupStatus status = inv.getArgument(0);
            LocalDateTime before = inv.getArgument(1);
            return store.values().stream()
                    .filter(r -> r.getStatus() == status && r.getCreatedAt().isBefore(before))
                    .toList();
        });
        when(repository.findByDeleteState(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(r -> inv.getArgument(0).equals(r.getDeleteState())).toList());
        when(repository.claimDeletion(anyLong(), any())).thenAnswer(inv -> {
            BackupRecord r = store.get(inv.getArgument(0));
            if (r == null || r.getDeleteState() != null) {
                return 0;
            }
            if (r.getRestoreStatus() == RestoreStatus.RESTORING) {
                return 0;
            }
            r.setDeleteState("RETIRING");
            r.setDeleteClaimedAt(LocalDateTime.now());
            return 1;
        });
        doAnswer(inv -> { store.remove(((BackupRecord) inv.getArgument(0)).getId()); return null; })
                .when(repository).delete(any(BackupRecord.class));
        when(repository.existsByFileName(anyString())).thenAnswer(inv ->
                store.values().stream().anyMatch(r -> r.getFileName().equals(inv.getArgument(0))));

        service = new BackupRetentionService(repository, instances, properties);
    }

    private BackupRecord record(long id, BackupStatus status, int ageMinutes, boolean fileExists) throws IOException {
        BackupRecord r = new BackupRecord();
        r.setId(id);
        r.setInstanceId(1L);
        r.setFileName("backup-" + id + ".jsonl");
        r.setStatus(status);
        r.setCreatedAt(LocalDateTime.now().minusMinutes(ageMinutes));
        r.setRestoreStatus(RestoreStatus.NONE);
        store.put(id, r);
        if (fileExists) {
            Files.writeString(dir.resolve(r.getFileName()), "x");
        }
        return r;
    }

    @Test
    void 超过保留数量时只清理最旧的成功备份() throws Exception {
        record(1, BackupStatus.SUCCESS, 50, true);
        record(2, BackupStatus.SUCCESS, 40, true);
        record(3, BackupStatus.SUCCESS, 30, true);
        record(4, BackupStatus.SUCCESS, 20, true);
        record(5, BackupStatus.SUCCESS, 10, true);

        var report = service.purge();

        assertThat(report.deleted()).isEqualTo(3);
        assertThat(store.keySet()).containsExactlyInAnyOrder(4L, 5L);
        assertThat(Files.exists(dir.resolve("backup-1.jsonl"))).isFalse();
        assertThat(Files.exists(dir.resolve("backup-5.jsonl"))).isTrue();
    }

    @Test
    void 运行中的备份不被清理() throws Exception {
        record(1, BackupStatus.RUNNING, 90, true);
        record(2, BackupStatus.SUCCESS, 10, true);

        service.purge();

        assertThat(store).containsKey(1L);
        assertThat(Files.exists(dir.resolve("backup-1.jsonl"))).isTrue();
    }

    @Test
    void 正在恢复的备份不被清理() throws Exception {
        BackupRecord restoring = record(1, BackupStatus.SUCCESS, 90, true);
        restoring.setRestoreStatus(RestoreStatus.RESTORING);
        record(2, BackupStatus.SUCCESS, 10, true);
        record(3, BackupStatus.SUCCESS, 20, true);

        var report = service.purge();

        assertThat(store).containsKey(1L);
        assertThat(report.deleted()).isEqualTo(0);
        assertThat(Files.exists(dir.resolve("backup-1.jsonl"))).isTrue();
    }

    @Test
    void 失败记录按天数清理且不占成功名额() throws Exception {
        record(1, BackupStatus.FAILED, 60 * 24 * 10, true);
        record(2, BackupStatus.FAILED, 60, true);
        record(3, BackupStatus.SUCCESS, 10, true);

        service.purge();

        assertThat(store).doesNotContainKey(1L);
        assertThat(store).containsKey(2L);
        assertThat(store).containsKey(3L);
    }

    @Test
    void 唯一可恢复备份永远不会被清理() throws Exception {
        properties.getBackup().getRetention().setDefaultCount(0);
        record(1, BackupStatus.SUCCESS, 90, true);
        record(2, BackupStatus.SUCCESS, 10, true);

        service.purge();

        assertThat(store).containsKey(2L);
        assertThat(store).doesNotContainKey(1L);
    }

    @Test
    void 文件删除失败时保留记录并可重试() throws Exception {
        record(1, BackupStatus.SUCCESS, 50, false);
        record(2, BackupStatus.SUCCESS, 40, false);
        record(3, BackupStatus.SUCCESS, 10, false);
        // 用"非空目录"占住文件名，制造删除失败
        Path blocker = dir.resolve("backup-1.jsonl");
        Files.createDirectory(blocker);
        Files.writeString(blocker.resolve("inner"), "x");

        var first = service.purge();
        assertThat(first.failed()).isEqualTo(1);
        assertThat(store).containsKey(1L);

        Files.delete(blocker.resolve("inner"));
        Files.delete(blocker);
        Files.writeString(blocker, "x");
        // 认领超时后由本实例接手（模拟 30 分钟后的下一轮清理）
        store.get(1L).setDeleteClaimedAt(LocalDateTime.now().minusMinutes(31));
        var second = service.purge();
        assertThat(second.deleted()).isEqualTo(1);
        assertThat(store).doesNotContainKey(1L);
    }

    @Test
    void 认领被其他实例持有时本实例不重复删除() throws Exception {
        properties.getBackup().getRetention().setDefaultCount(1);
        BackupRecord r = record(1, BackupStatus.SUCCESS, 90, true);
        record(2, BackupStatus.SUCCESS, 10, true);
        // 另一个实例刚刚认领（RETIRING），仍在处理中
        r.setDeleteState("RETIRING");
        r.setDeleteClaimedAt(LocalDateTime.now());

        when(repository.claimDeletion(anyLong(), any())).thenReturn(0);
        when(repository.findByDeleteState("RETIRING")).thenReturn(List.of(r));

        var report = service.purge();

        // 本实例认领失败 → 跳过；收尾由已认领的实例完成
        assertThat(report.skipped()).isGreaterThanOrEqualTo(1);
        verify(repository, never()).delete(r);
    }

    @Test
    void 孤儿文件超过宽限期才删除() throws Exception {
        record(1, BackupStatus.SUCCESS, 10, true);
        Path orphan = dir.resolve("backup-orphan.jsonl");
        Files.writeString(orphan, "x");

        var first = service.purge();
        assertThat(first.orphanRemoved()).isEqualTo(0);
        assertThat(Files.exists(orphan)).isTrue();

        Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 2 * 3600_000L));
        var second = service.purge();
        assertThat(second.orphanRemoved()).isEqualTo(1);
        assertThat(Files.exists(orphan)).isFalse();
    }

    @Test
    void 手工删除运行中或恢复中的备份被拒绝() throws Exception {
        BackupRecord running = record(1, BackupStatus.RUNNING, 1, true);
        BackupRecord restoring = record(2, BackupStatus.SUCCESS, 1, true);
        restoring.setRestoreStatus(RestoreStatus.RESTORING);

        assertThatThrownBy(() -> service.delete(running.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能删除");
        assertThatThrownBy(() -> service.delete(restoring.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正在被恢复");
    }

    @Test
    void 手工删除成功记录会同时删除文件与记录() throws Exception {
        record(1, BackupStatus.SUCCESS, 5, true);
        record(2, BackupStatus.SUCCESS, 1, true);

        service.delete(1L);

        assertThat(store).doesNotContainKey(1L);
        assertThat(Files.exists(dir.resolve("backup-1.jsonl"))).isFalse();
    }
}