package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Wave 3B M2：BackupService 门面黑盒行为测试（锁互斥、容量回滚、下载审计、本地锁清理）。 */
class BackupServiceFacadeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void enforcesExportLeaseConflictStaleRecoveryAndCapacityRollback(@TempDir Path dir) {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instSvc = mock(InstanceService.class);
        KeyspaceService ksSvc = mock(KeyspaceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        BackupCapacityService capSvc = mock(BackupCapacityService.class);
        DbLeaseLockService leaseSvc = mock(DbLeaseLockService.class);
        ConsoleMetricsBinder binder = mock(ConsoleMetricsBinder.class);

        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(dir.toString());
        props.getBackup().setCompress(false);
        LocalBackupStorageBackend storage = new LocalBackupStorageBackend(props);
        Executor direct = Runnable::run;

        BackupService svc = new BackupService(
                repo, instSvc, ksSvc, gateway, MAPPER, props, storage, direct, audit, capSvc, leaseSvc);
        svc.setMetricsBinder(binder);

        DbInstance inst = instance(1L, "facade-node");
        when(instSvc.require(1L)).thenReturn(inst);
        when(repo.save(any(BackupRecord.class))).thenAnswer(inv -> {
            BackupRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(100L);
            }
            return r;
        });

        // 1. Cross-replica lease lock conflict -> 409
        when(leaseSvc.tryAcquire(eq("backup-op:instance:1"), any())).thenReturn(false);
        assertThatThrownBy(() -> svc.export(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("正在提交");

        // 2. Fresh active RESTORING blocks export -> 409
        when(leaseSvc.tryAcquire(eq("backup-op:instance:1"), any())).thenReturn(true);
        BackupRecord freshRestoring = new BackupRecord();
        freshRestoring.setId(11L);
        freshRestoring.setRestoreStatus(RestoreStatus.RESTORING);
        freshRestoring.setRestoreStartedAt(LocalDateTime.now());
        when(repo.findFirstByInstanceIdAndRestoreStatusOrderByRestoreStartedAtDesc(1L, RestoreStatus.RESTORING))
                .thenReturn(Optional.of(freshRestoring));
        assertThatThrownBy(() -> svc.export(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("正在恢复");

        // 3. Stale RESTORING gets marked FAILED; fresh RUNNING blocks export -> 409
        freshRestoring.setRestoreStartedAt(LocalDateTime.now().minusHours(2));
        BackupRecord freshRunning = new BackupRecord();
        freshRunning.setId(12L);
        freshRunning.setStatus(BackupStatus.RUNNING);
        freshRunning.setCreatedAt(LocalDateTime.now());
        when(repo.findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(1L, BackupStatus.RUNNING))
                .thenReturn(Optional.of(freshRunning));
        assertThatThrownBy(() -> svc.export(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已有备份任务");
        assertThat(freshRestoring.getRestoreStatus()).isEqualTo(RestoreStatus.FAILED);

        // 4. Stale RUNNING gets marked FAILED; capacity reservation error marks new record FAILED
        freshRunning.setCreatedAt(LocalDateTime.now().minusHours(3));
        doThrow(BusinessException.badRequest("磁盘空间不足")).when(capSvc).reserve(eq(1L), anyLong(), anyLong());
        assertThatThrownBy(() -> svc.export(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("磁盘空间不足");
        assertThat(freshRunning.getStatus()).isEqualTo(BackupStatus.FAILED);

        // 5. Local lock reconciliation evicts deleted instance locks cleanly
        assertThat(svc.reconcileInstanceLocks(Set.of(999L))).isEqualTo(1);
        svc.evictInstanceLock(1L);
    }

    @Test
    void openDownloadValidatesExistenceStreamsBytesAndWritesAudit(@TempDir Path dir) throws Exception {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instSvc = mock(InstanceService.class);
        AuditService audit = mock(AuditService.class);
        DbInstance inst = instance(1L, "dl-node");
        when(instSvc.require(1L)).thenReturn(inst);

        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(dir.toString());
        LocalBackupStorageBackend storage = new LocalBackupStorageBackend(props);
        publish(storage, "dl.jsonl", "{\"key\":\"k\"}\n".getBytes(StandardCharsets.UTF_8));

        BackupService svc = new BackupService(
                repo, instSvc, mock(KeyspaceService.class), mock(RespGateway.class), MAPPER, props,
                storage, Runnable::run, audit, mock(BackupCapacityService.class));

        when(repo.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.openDownload(404L)).isInstanceOf(BusinessException.class);

        BackupRecord missing = new BackupRecord();
        missing.setId(22L);
        missing.setInstanceId(1L);
        missing.setFileName("no-such.jsonl");
        when(repo.findById(22L)).thenReturn(Optional.of(missing));
        assertThatThrownBy(() -> svc.openDownload(22L)).isInstanceOf(BusinessException.class);

        BackupRecord ok = new BackupRecord();
        ok.setId(23L);
        ok.setInstanceId(1L);
        ok.setFileName("dl.jsonl");
        when(repo.findById(23L)).thenReturn(Optional.of(ok));
        try (var dl = svc.openDownload(23L).inputStream()) {
            assertThat(new String(dl.readAllBytes(), StandardCharsets.UTF_8)).contains("\"k\"");
        }
        verify(audit).record("DOWNLOAD_BACKUP", "dl-node", "dl.jsonl", true);
    }

    private static void publish(LocalBackupStorageBackend storage, String name, byte[] data) throws IOException {
        try (OutputStream out = storage.openTempOutput(name)) {
            out.write(data);
        }
        storage.publish(name);
    }

    private static DbInstance instance(Long id, String name) {
        DbInstance inst = new DbInstance();
        inst.setId(id);
        inst.setName(name);
        inst.setHost("127.0.0.1");
        inst.setPort(6379);
        return inst;
    }
}
