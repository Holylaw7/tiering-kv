package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.BackupVerifyStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Wave 3B M2：BackupExportExecutor 协作者独立行为单测。 */
class BackupExportExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ignoresMissingRecordGracefully(@TempDir Path dir) {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        when(repo.findById(999L)).thenReturn(Optional.empty());
        BackupExportExecutor executor = newExecutor(dir, repo, mock(InstanceService.class),
                mock(KeyspaceService.class), mock(RespGateway.class), mock(BackupCapacityService.class), false, 0L);

        executor.runExport(999L);

        verify(repo).findById(999L);
    }

    @Test
    void exportsCompressedArchiveComputesSha256AndRecordsMetrics(@TempDir Path dir) throws Exception {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instSvc = mock(InstanceService.class);
        KeyspaceService ksSvc = mock(KeyspaceService.class);
        RespGateway gateway = mock(RespGateway.class);
        BackupCapacityService capSvc = mock(BackupCapacityService.class);
        ConsoleMetricsBinder binder = mock(ConsoleMetricsBinder.class);

        DbInstance inst = instance(1L, "export-node");
        when(instSvc.require(1L)).thenReturn(inst);

        BackupRecord rec = new BackupRecord();
        rec.setId(10L);
        rec.setInstanceId(1L);
        rec.setFileName("ok-export.jsonl.gz");
        when(repo.findById(10L)).thenReturn(Optional.of(rec));
        when(gateway.execute(eq(inst), eq("SCAN"), any(), any(), any(), any(), any()))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("k1".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("gone".getBytes(StandardCharsets.UTF_8)))))));
        when(ksSvc.inspectBatch(eq(inst), any())).thenReturn(List.of(
                new KeyEntry("k1", "string", 60),
                new KeyEntry("gone", "none", -2)));
        when(ksSvc.readValuesBatch(eq(inst), any())).thenReturn(Arrays.asList("val-1", null));

        BackupExportExecutor executor = newExecutor(dir, repo, instSvc, ksSvc, gateway, capSvc, true, 0L);
        executor.setMetricsBinder(binder);

        executor.runExport(10L);

        assertThat(rec.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(rec.getKeyCount()).isEqualTo(1L);
        assertThat(rec.getSizeBytes()).isGreaterThan(0L);
        assertThat(rec.getSha256()).matches("[0-9a-f]{64}");
        assertThat(rec.getVerifyStatus()).isEqualTo(BackupVerifyStatus.VERIFIED);
        verify(capSvc).release(eq(10L), anyLong());
        verify(binder).recordBackup("SUCCESS");
    }

    @Test
    void marksFailedOnScanErrorMalformedReplyAndMaxKeysLimit(@TempDir Path dir) throws Exception {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instSvc = mock(InstanceService.class);
        KeyspaceService ksSvc = mock(KeyspaceService.class);
        RespGateway gateway = mock(RespGateway.class);
        BackupCapacityService capSvc = mock(BackupCapacityService.class);
        ConsoleMetricsBinder binder = mock(ConsoleMetricsBinder.class);

        DbInstance inst = instance(1L, "export-fail-node");
        when(instSvc.require(1L)).thenReturn(inst);

        BackupRecord rec = new BackupRecord();
        rec.setId(20L);
        rec.setInstanceId(1L);
        rec.setFileName("fail.jsonl");
        when(repo.findById(20L)).thenReturn(Optional.of(rec));

        // 1. SCAN RESP Error
        when(gateway.execute(eq(inst), eq("SCAN"), any(), any(), any(), any(), any()))
                .thenReturn(new RespReply.Error("ERR scan denied"));
        BackupExportExecutor executor = newExecutor(dir, repo, instSvc, ksSvc, gateway, capSvc, false, 0L);
        executor.setMetricsBinder(binder);
        executor.runExport(20L);
        assertThat(rec.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(rec.getMessage()).contains("SCAN 失败");
        verify(binder).recordBackup("FAILED");

        // 2. Malformed SCAN reply
        when(gateway.execute(eq(inst), eq("SCAN"), any(), any(), any(), any(), any()))
                .thenReturn(new RespReply.Simple("not-array"));
        executor.runExport(20L);
        assertThat(rec.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(rec.getMessage()).contains("SCAN 返回结构异常");

        // 3. maxKeys=1 limit reached
        BackupExportExecutor limited = newExecutor(dir, repo, instSvc, ksSvc, gateway, capSvc, false, 1L);
        when(gateway.execute(eq(inst), eq("SCAN"), any(), any(), any(), any(), any()))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("k1".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("k2".getBytes(StandardCharsets.UTF_8)))))));
        when(ksSvc.inspectBatch(eq(inst), any())).thenReturn(List.of(
                new KeyEntry("k1", "none", -2),
                new KeyEntry("k2", "string", -1)));
        when(ksSvc.readValuesBatch(eq(inst), any())).thenReturn(Arrays.asList(null, "v2"));
        limited.runExport(20L);
        assertThat(rec.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(rec.getMessage()).contains("超过配置上限");
    }

    private static BackupExportExecutor newExecutor(Path dir, BackupRecordRepository repo,
                                                    InstanceService instSvc, KeyspaceService ksSvc,
                                                    RespGateway gateway, BackupCapacityService capSvc,
                                                    boolean compress, long maxKeys) {
        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(dir.toString());
        props.getBackup().setCompress(compress);
        props.getBackup().setMaxKeys(maxKeys);
        return new BackupExportExecutor(repo, instSvc, ksSvc, gateway, MAPPER, props,
                new LocalBackupStorageBackend(props), capSvc);
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
