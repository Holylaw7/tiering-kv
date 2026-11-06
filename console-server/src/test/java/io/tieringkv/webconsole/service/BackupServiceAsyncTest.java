package io.tieringkv.webconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TD-091：备份异步化 + 同实例并发 409。 */
class BackupServiceAsyncTest {

    @TempDir
    Path dir;

    private BackupRecordRepository repository;
    private InstanceService instanceService;
    private RespGateway gateway;
    private ConsoleProperties properties;
    private DbInstance instance;

    @BeforeEach
    void setUp() {
        repository = mock(BackupRecordRepository.class);
        instanceService = mock(InstanceService.class);
        gateway = mock(RespGateway.class);
        properties = new ConsoleProperties();
        properties.getBackup().setDir(dir.resolve("backups").toString());
        instance = new DbInstance();
        instance.setName("local");
        when(instanceService.require(1L)).thenReturn(instance);
        when(repository.save(any(BackupRecord.class))).thenAnswer(invocation -> {
            BackupRecord record = invocation.getArgument(0);
            if (record.getId() == null) {
                record.setId(42L);
            }
            return record;
        });
    }

    private KeyspaceService keyspaceService;
    private AuditService auditService;

    private BackupService service(Executor executor) {
        if (keyspaceService == null) {
            keyspaceService = mock(KeyspaceService.class);
        }
        if (auditService == null) {
            auditService = mock(AuditService.class);
        }
        return new BackupService(repository, instanceService, keyspaceService,
                gateway, new ObjectMapper(), properties, executor, auditService);
    }

    private BackupRecord runningRecord(LocalDateTime createdAt) {
        BackupRecord record = new BackupRecord();
        record.setId(7L);
        record.setInstanceId(1L);
        record.setFileName("backup-1-x.jsonl");
        record.setStatus(BackupStatus.RUNNING);
        record.setCreatedAt(createdAt);
        return record;
    }

    @Test
    void rejectsSecondBackupWhileOneIsRunning() {
        when(repository.findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(1L, BackupStatus.RUNNING))
                .thenReturn(Optional.of(runningRecord(LocalDateTime.now())));
        List<Runnable> queued = new ArrayList<>();

        assertThatThrownBy(() -> service(queued::add).export(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有备份任务在执行")
                .hasMessageContaining("id=7");
        assertThat(queued).isEmpty();
    }

    @Test
    void simultaneousRequestsAreSerializedToOneBackup() throws Exception {
        // 模拟真实仓库：保存后即可被后续查询看到（RUNNING 状态）
        java.util.concurrent.atomic.AtomicReference<BackupRecord> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(repository.findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(1L, BackupStatus.RUNNING))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(BackupRecord.class))).thenAnswer(invocation -> {
            BackupRecord record = invocation.getArgument(0);
            if (record.getId() == null) {
                record.setId(42L);
            }
            if (record.getStatus() == BackupStatus.RUNNING) {
                stored.set(record);
            }
            return record;
        });

        List<Runnable> queued = java.util.Collections.synchronizedList(new ArrayList<>());
        BackupService service = service(queued::add);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflict = new java.util.concurrent.atomic.AtomicInteger();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    service.export(1L);
                    success.incrementAndGet();
                } catch (BusinessException ex) {
                    conflict.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
        }

        assertThat(success.get()).as("只允许一次成功提交").isEqualTo(1);
        assertThat(conflict.get()).as("另一个请求必须被 409 拒绝").isEqualTo(1);
        assertThat(queued).as("只能提交一个导出任务").hasSize(1);
    }

    @Test
    void createBackupWritesAudit() {
        when(repository.findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(1L, BackupStatus.RUNNING))
                .thenReturn(Optional.empty());
        BackupService service = service(Runnable::run);

        service.export(1L);

        verify(auditService).record(eq("CREATE_BACKUP"), anyString(), anyString(), eq(true));
    }

    @Test
    void allowsBackupWhenPreviousRunIsStale() {
        when(repository.findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(1L, BackupStatus.RUNNING))
                .thenReturn(Optional.of(runningRecord(LocalDateTime.now().minusHours(2))));
        List<Runnable> queued = new ArrayList<>();

        BackupRecord created = service(queued::add).export(1L);

        assertThat(created.getStatus()).isEqualTo(BackupStatus.RUNNING);
        assertThat(queued).hasSize(1);
    }

    @Test
    void returnsImmediatelyAndRunsOnExecutor() throws Exception {
        when(repository.findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        List<Runnable> queued = new ArrayList<>();
        BackupRecord created = service(queued::add).export(1L);

        assertThat(created.getStatus()).isEqualTo(BackupStatus.RUNNING);
        assertThat(queued).hasSize(1);
        verify(repository, never()).findById(anyLong());
    }

    @Test
    void runExportMarksSuccessWithKeyCount() throws Exception {
        BackupRecord record = runningRecord(LocalDateTime.now());
        when(repository.findById(7L)).thenReturn(Optional.of(record));
        when(gateway.execute(eq(instance), eq("SCAN"), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes()),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("k1".getBytes()),
                                new RespReply.Bulk("k2".getBytes()))))));
        BackupService service = service(Runnable::run);
        when(keyspaceService.type(instance, "k1")).thenReturn("string");
        when(keyspaceService.type(instance, "k2")).thenReturn("string");
        when(keyspaceService.ttl(instance, "k1")).thenReturn(-1L);
        when(keyspaceService.ttl(instance, "k2")).thenReturn(-1L);
        when(keyspaceService.value(instance, "k1", "string")).thenReturn("v1");
        when(keyspaceService.value(instance, "k2", "string")).thenReturn("v2");

        service.runExport(7L);

        assertThat(record.getStatus())
                .as("导出失败原因：%s", record.getMessage())
                .isEqualTo(BackupStatus.SUCCESS);
        assertThat(record.getKeyCount()).isEqualTo(2);
        assertThat(record.getFinishedAt()).isNotNull();
        assertThat(record.getMessage()).contains("导出 2 个键");
    }
}