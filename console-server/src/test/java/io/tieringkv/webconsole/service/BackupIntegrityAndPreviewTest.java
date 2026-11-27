package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.BackupVerifyStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v6.4.0 Stage D1：归档 SHA-256 完整性校验、防篡改识别、Dry-Run 冲突预览与预恢复保护快照验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackupIntegrityAndPreviewTest {

    @TempDir
    Path tempDir;

    @Autowired
    private BackupRecordRepository backupRepository;

    @Autowired
    private DbInstanceRepository instanceRepository;

    @Autowired
    private InstanceService instanceService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    private DbInstance instance;

    @BeforeEach
    void setUp() {
        backupRepository.deleteAll();
        instance = instanceRepository.findAll().stream().findFirst().orElseGet(() -> {
            DbInstance created = new DbInstance();
            created.setName("d1-test-inst");
            created.setHost("127.0.0.1");
            created.setPort(6379);
            created.setEnabled(true);
            return instanceRepository.save(created);
        });
    }

    private BackupService newSyncBackupService(RespGateway gateway, KeyspaceService keyspaceService) {
        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(tempDir.toString());
        props.getBackup().setCompress(true);
        BackupStorageBackend backend = new LocalBackupStorageBackend(props);
        BackupCapacityService capacityService = mock(BackupCapacityService.class);
        when(capacityService.estimate(any())).thenReturn(0L);
        return new BackupService(
                backupRepository,
                instanceService,
                keyspaceService,
                gateway,
                objectMapper,
                props,
                backend,
                Runnable::run,
                auditService,
                capacityService,
                null);
    }

    @Test
    void exportComputesSha256AndVerifyDetectsTampering() throws Exception {
        RespGateway gateway = mock(RespGateway.class);
        KeyspaceService keyspace = mock(KeyspaceService.class);
        when(gateway.execute(any(DbInstance.class), eq("SCAN"), eq("0"), eq("MATCH"), eq("*"), eq("COUNT"), any()))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("user:1".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("cfg:mode".getBytes(StandardCharsets.UTF_8)))))));
        when(keyspace.inspectBatch(any(DbInstance.class), anyList())).thenReturn(List.of(
                new KeyEntry("user:1", "string", -1L),
                new KeyEntry("cfg:mode", "hash", 300L)));
        when(keyspace.readValuesBatch(any(DbInstance.class), anyList())).thenReturn(List.of(
                "alice",
                Map.of("env", "prod")));

        BackupService service = newSyncBackupService(gateway, keyspace);
        BackupRecord exported = service.export(instance.getId());
        BackupRecord persisted = backupRepository.findById(exported.getId()).orElseThrow();

        assertThat(persisted.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(persisted.getSha256()).isNotNull().hasSize(64);
        assertThat(persisted.getVerifyStatus()).isEqualTo(BackupVerifyStatus.VERIFIED);
        assertThat(persisted.getVerifiedAt()).isNotNull();

        // 1. 正常完整性校验通过
        BackupService.BackupVerifyResult ok = service.verify(persisted.getId());
        assertThat(ok.status()).isEqualTo(BackupVerifyStatus.VERIFIED);
        assertThat(ok.actualSha256()).isEqualTo(persisted.getSha256());
        assertThat(ok.validEntries()).isEqualTo(2L);
        assertThat(ok.sizeBytes()).isPositive();

        // 2. 篡改归档 1 字节 -> 立即识别为 CORRUPTED
        Path archive = tempDir.resolve(persisted.getFileName());
        byte[] bytes = Files.readAllBytes(archive);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(archive, bytes);

        BackupService.BackupVerifyResult tampered = service.verify(persisted.getId());
        assertThat(tampered.status()).isEqualTo(BackupVerifyStatus.CORRUPTED);
        assertThat(tampered.actualSha256()).isNotEqualTo(persisted.getSha256());
        assertThat(backupRepository.findById(persisted.getId()).orElseThrow().getVerifyStatus())
                .isEqualTo(BackupVerifyStatus.CORRUPTED);
    }

    @Test
    void previewRestoreCountsConflictsNewKeysAndTypesWithoutMutation() throws Exception {
        RespGateway gateway = mock(RespGateway.class);
        KeyspaceService keyspace = mock(KeyspaceService.class);
        when(gateway.execute(any(DbInstance.class), eq("SCAN"), eq("0"), eq("MATCH"), eq("*"), eq("COUNT"), any()))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("k:exist".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("k:fresh".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("h:exist".getBytes(StandardCharsets.UTF_8)))))));
        when(keyspace.inspectBatch(any(DbInstance.class), anyList())).thenReturn(List.of(
                new KeyEntry("k:exist", "string", -1L),
                new KeyEntry("k:fresh", "string", 60L),
                new KeyEntry("h:exist", "hash", -1L)));
        when(keyspace.readValuesBatch(any(DbInstance.class), anyList())).thenReturn(List.of(
                "v1", "v2", Map.of("f", "v")));
        // EXISTS 管线返回：[1, 0, 1] -> 2 个冲突键 + 1 个新增键
        when(gateway.executeBatch(any(DbInstance.class), anyList())).thenReturn(List.of(
                new RespReply.Integer(1L),
                new RespReply.Integer(0L),
                new RespReply.Integer(1L)));

        BackupService service = newSyncBackupService(gateway, keyspace);
        BackupRecord exported = service.export(instance.getId());

        BackupService.RestorePreviewView preview = service.previewRestore(exported.getId());
        assertThat(preview.archiveReadable()).isTrue();
        assertThat(preview.verifyStatus()).isEqualTo(BackupVerifyStatus.VERIFIED);
        assertThat(preview.totalKeys()).isEqualTo(3L);
        assertThat(preview.conflictKeys()).isEqualTo(2L);
        assertThat(preview.newKeys()).isEqualTo(1L);
        assertThat(preview.expiredKeys()).isZero();
        assertThat(preview.typeBreakdown()).containsEntry("string", 2L).containsEntry("hash", 1L);
        assertThat(preview.sampleConflicts()).containsExactly("k:exist", "h:exist");
        assertThat(preview.warning()).contains("2 个同名键将被覆盖");
    }

    @Test
    void restoreWithSafetySnapshotCreatesPreRestoreBackupAndLinksId() throws Exception {
        RespGateway gateway = mock(RespGateway.class);
        KeyspaceService keyspace = mock(KeyspaceService.class);
        when(gateway.execute(any(DbInstance.class), eq("SCAN"), eq("0"), eq("MATCH"), eq("*"), eq("COUNT"), any()))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("k:1".getBytes(StandardCharsets.UTF_8)))))));
        when(keyspace.inspectBatch(any(DbInstance.class), anyList())).thenReturn(List.of(
                new KeyEntry("k:1", "string", -1L)));
        when(keyspace.readValuesBatch(any(DbInstance.class), anyList())).thenReturn(List.of("v1"));
        when(gateway.executeBatch(any(DbInstance.class), anyList())).thenAnswer(inv -> {
            List<?> cmds = inv.getArgument(1);
            List<RespReply> replies = new ArrayList<>(cmds.size());
            for (int i = 0; i < cmds.size(); i++) {
                replies.add(new RespReply.Simple("OK"));
            }
            return replies;
        });

        BackupService service = newSyncBackupService(gateway, keyspace);
        BackupRecord base = service.export(instance.getId());

        BackupRecord restored = service.restore(base.getId(), true);
        BackupRecord latest = backupRepository.findById(restored.getId()).orElseThrow();
        assertThat(latest.getRestoreStatus()).isEqualTo(RestoreStatus.SUCCEEDED);
        assertThat(latest.getRestoredKeys()).isEqualTo(1L);
        assertThat(latest.getPreRestoreBackupId()).isNotNull();

        BackupRecord safety = backupRepository.findById(latest.getPreRestoreBackupId()).orElseThrow();
        assertThat(safety.getFileName()).startsWith("pre-restore-");
        assertThat(safety.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(safety.getVerifyStatus()).isEqualTo(BackupVerifyStatus.VERIFIED);
        assertThat(safety.getSha256()).hasSize(64);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerForbiddenFromVerifyAndPreviewEndpoints() throws Exception {
        mockMvc.perform(post("/api/v1/backups/1/verify"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/backups/1/restore/preview"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPS")
    void opsForbiddenFromRestorePreviewButAllowedVerifyRoute() throws Exception {
        mockMvc.perform(post("/api/v1/backups/999999/restore/preview"))
                .andExpect(status().isForbidden());
        // OPS 可访问 verify（不存在返回 404，而非 403）
        mockMvc.perform(post("/api/v1/backups/999999/verify")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
}
