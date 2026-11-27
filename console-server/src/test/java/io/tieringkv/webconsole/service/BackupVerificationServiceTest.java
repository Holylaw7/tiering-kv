package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.BackupVerifyStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Wave 3B M2：BackupVerificationService 协作者独立行为单测（SHA-256 校验与 Dry-Run 冲突预览）。 */
class BackupVerificationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void verifiesPlainGzipBackfillTamperingAndCorruptedArchives(@TempDir Path dir) throws Exception {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instSvc = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        DbInstance inst = instance(1L, "verify-node");
        when(instSvc.require(1L)).thenReturn(inst);

        LocalBackupStorageBackend storage = storage(dir);
        BackupVerificationService svc = new BackupVerificationService(repo, instSvc, gateway, MAPPER, storage, audit);

        // Precondition guards
        when(repo.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.verify(404L)).isInstanceOf(BusinessException.class);

        BackupRecord failedRec = record(21L, BackupStatus.FAILED, "x.jsonl");
        when(repo.findById(21L)).thenReturn(Optional.of(failedRec));
        assertThatThrownBy(() -> svc.verify(21L)).isInstanceOf(BusinessException.class).hasMessageContaining("仅成功的备份");

        BackupRecord missingFile = record(22L, BackupStatus.SUCCESS, "missing.jsonl");
        when(repo.findById(22L)).thenReturn(Optional.of(missingFile));
        assertThatThrownBy(() -> svc.verify(22L)).isInstanceOf(BusinessException.class).hasMessageContaining("不存在");

        // Legacy archive without SHA-256 -> verified and backfilled
        String validContent = "\n{\"key\":\"k1\",\"type\":\"string\",\"ttl\":60,\"value\":\"v1\"}\n";
        publish(storage, "ok.jsonl", validContent.getBytes(StandardCharsets.UTF_8));
        BackupRecord legacy = record(23L, BackupStatus.SUCCESS, "ok.jsonl");
        when(repo.findById(23L)).thenReturn(Optional.of(legacy));
        var legacyRes = svc.verify(23L);
        assertThat(legacyRes.status()).isEqualTo(BackupVerifyStatus.VERIFIED);
        assertThat(legacyRes.validEntries()).isEqualTo(1L);
        assertThat(legacy.getSha256()).matches("[0-9a-f]{64}");

        // SHA mismatch -> CORRUPTED
        legacy.setSha256("0".repeat(64));
        assertThat(svc.verify(23L).status()).isEqualTo(BackupVerifyStatus.CORRUPTED);

        // Missing key/type structural error -> CORRUPTED
        publish(storage, "bad.jsonl", "{\"key\":null,\"type\":\"\"}\n".getBytes(StandardCharsets.UTF_8));
        BackupRecord bad = record(24L, BackupStatus.SUCCESS, "bad.jsonl");
        when(repo.findById(24L)).thenReturn(Optional.of(bad));
        assertThat(svc.verify(24L).status()).isEqualTo(BackupVerifyStatus.CORRUPTED);

        // GZIP archive with matching SHA -> VERIFIED
        ByteArrayOutputStream gzBuf = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(gzBuf)) {
            gz.write(validContent.getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzBytes = gzBuf.toByteArray();
        publish(storage, "ok.jsonl.gz", gzBytes);
        BackupRecord gzRec = record(25L, BackupStatus.SUCCESS, "ok.jsonl.gz");
        gzRec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(gzBytes)));
        when(repo.findById(25L)).thenReturn(Optional.of(gzRec));
        assertThat(svc.verify(25L).status()).isEqualTo(BackupVerifyStatus.VERIFIED);
    }

    @Test
    void previewsConflictsExpiredKeysCorruptedWarningAndPipelineMismatch(@TempDir Path dir) throws Exception {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instSvc = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        DbInstance inst = instance(1L, "preview-node");
        when(instSvc.require(1L)).thenReturn(inst);

        LocalBackupStorageBackend storage = storage(dir);
        BackupVerificationService svc = new BackupVerificationService(repo, instSvc, gateway, MAPPER, storage, audit);

        BackupRecord failedRec = record(21L, BackupStatus.FAILED, "x.jsonl");
        when(repo.findById(21L)).thenReturn(Optional.of(failedRec));
        assertThatThrownBy(() -> svc.previewRestore(21L)).isInstanceOf(BusinessException.class);

        BackupRecord missingRec = record(22L, BackupStatus.SUCCESS, "gone.jsonl");
        when(repo.findById(22L)).thenReturn(Optional.of(missingRec));
        assertThatThrownBy(() -> svc.previewRestore(22L)).isInstanceOf(BusinessException.class);

        String previewData = "\n"
                + "{\"key\":\"k1\",\"type\":\"string\",\"ttl\":-1,\"value\":\"v1\"}\n"
                + "{\"key\":\"k2\",\"type\":\"\",\"ttl\":-1,\"value\":\"v2\"}\n"
                + "{\"key\":\"exp\",\"type\":\"string\",\"ttl\":-2,\"value\":\"gone\"}\n";
        publish(storage, "prev.jsonl", previewData.getBytes(StandardCharsets.UTF_8));
        BackupRecord prevRec = record(26L, BackupStatus.SUCCESS, "prev.jsonl");
        prevRec.setVerifyStatus(BackupVerifyStatus.VERIFIED);
        when(repo.findById(26L)).thenReturn(Optional.of(prevRec));
        when(gateway.executeBatch(eq(inst), any()))
                .thenReturn(List.of(new RespReply.Integer(1), new RespReply.Integer(0)));

        var pv = svc.previewRestore(26L);
        assertThat(pv.archiveReadable()).isTrue();
        assertThat(pv.totalKeys()).isEqualTo(3L);
        assertThat(pv.conflictKeys()).isEqualTo(1L);
        assertThat(pv.newKeys()).isEqualTo(1L);
        assertThat(pv.expiredKeys()).isEqualTo(1L);
        assertThat(pv.typeBreakdown()).containsEntry("string", 1L).containsEntry("unknown", 1L);
        assertThat(pv.sampleConflicts()).containsExactly("k1");
        assertThat(pv.warning()).contains("同名键");

        prevRec.setVerifyStatus(BackupVerifyStatus.CORRUPTED);
        assertThat(svc.previewRestore(26L).warning()).contains("CORRUPTED");

        when(gateway.executeBatch(eq(inst), any())).thenReturn(List.of());
        assertThat(svc.previewRestore(26L).archiveReadable()).isFalse();
    }

    private static LocalBackupStorageBackend storage(Path dir) {
        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(dir.toString());
        return new LocalBackupStorageBackend(props);
    }

    private static BackupRecord record(Long id, BackupStatus status, String fileName) {
        BackupRecord rec = new BackupRecord();
        rec.setId(id);
        rec.setInstanceId(1L);
        rec.setStatus(status);
        rec.setFileName(fileName);
        return rec;
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
