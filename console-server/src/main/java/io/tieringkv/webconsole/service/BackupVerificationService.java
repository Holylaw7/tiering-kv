package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.BackupVerifyStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespClient;
import io.tieringkv.webconsole.resp.RespReply;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 逻辑备份归档校验与只读恢复预演服务（Wave 3B M2）：
 * 负责流式 SHA-256 完整性校验与基于 RESP Pipeline EXISTS 的 Dry-Run 冲突统计。
 */
@Service
public class BackupVerificationService {

    private static final int MAX_SAMPLE_CONFLICTS = 20;

    private final BackupRecordRepository repository;
    private final InstanceService instanceService;
    private final RespGateway gateway;
    private final ObjectMapper objectMapper;
    private final BackupStorageBackend storageBackend;
    private final AuditService auditService;

    public BackupVerificationService(BackupRecordRepository repository,
                                     InstanceService instanceService,
                                     RespGateway gateway,
                                     ObjectMapper objectMapper,
                                     BackupStorageBackend storageBackend,
                                     AuditService auditService) {
        this.repository = repository;
        this.instanceService = instanceService;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.storageBackend = storageBackend;
        this.auditService = auditService;
    }

    /** 校验逻辑备份归档的 SHA-256 摘要与 Gzip/JSONL 结构完整性（v6.4.0 D1）。 */
    public BackupService.BackupVerifyResult verify(Long recordId) {
        BackupRecord record = repository.findById(recordId)
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在：" + recordId));
        if (record.getStatus() != BackupStatus.SUCCESS) {
            throw BusinessException.badRequest("仅成功的备份可校验完整性");
        }
        String fileName = record.getFileName();
        storageBackend.validateFileName(fileName);
        if (!storageBackend.exists(fileName)) {
            throw BusinessException.notFound("备份文件不存在：" + fileName);
        }

        MessageDigest digest = BackupSnapshotSupport.sha256Digest();
        long validEntries = 0L;
        long totalBytes = 0L;
        String parseError = null;
        try (InputStream raw = new BufferedInputStream(storageBackend.openInput(fileName));
             DigestInputStream digestIn = new DigestInputStream(raw, digest)) {
            BackupSnapshotSupport.CountingInputStream counter =
                    new BackupSnapshotSupport.CountingInputStream(digestIn);
            try {
                validEntries = validateArchiveEntries(counter);
            } catch (IOException | RuntimeException ex) {
                parseError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            byte[] drainBuf = new byte[4096];
            while (counter.read(drainBuf) != -1) {
                // 完整消耗尾部字节，确保全文件 SHA-256 计算覆盖末尾篡改
            }
            totalBytes = counter.bytesRead();
        } catch (IOException ex) {
            parseError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        }

        String actualSha256 = HexFormat.of().formatHex(digest.digest());
        String expectedSha256 = record.getSha256();
        LocalDateTime now = LocalDateTime.now();
        BackupVerifyStatus status;
        String message;
        if (parseError != null) {
            status = BackupVerifyStatus.CORRUPTED;
            message = BackupSnapshotSupport.truncate("归档解压或结构解析失败：" + parseError);
        } else if (expectedSha256 != null && !expectedSha256.isBlank()
                && !expectedSha256.equalsIgnoreCase(actualSha256)) {
            status = BackupVerifyStatus.CORRUPTED;
            message = "SHA-256 摘要不匹配：归档可能已被篡改或损坏";
        } else if (expectedSha256 == null || expectedSha256.isBlank()) {
            status = BackupVerifyStatus.VERIFIED;
            record.setSha256(actualSha256);
            message = "历史归档结构校验通过并补录 SHA-256，共 " + validEntries + " 条记录";
        } else {
            status = BackupVerifyStatus.VERIFIED;
            message = "SHA-256 摘要与归档结构均校验通过，共 " + validEntries + " 条记录";
        }

        record.setVerifyStatus(status);
        record.setVerifiedAt(now);
        repository.save(record);
        boolean ok = status == BackupVerifyStatus.VERIFIED;
        auditService.record("VERIFY_BACKUP", BackupSnapshotSupport.instanceName(instanceService, record.getInstanceId()),
                "file=" + fileName + " status=" + status + " entries=" + validEntries, ok);
        return new BackupService.BackupVerifyResult(
                record.getId(), fileName, status,
                expectedSha256 != null && !expectedSha256.isBlank() ? expectedSha256 : actualSha256,
                actualSha256, validEntries, totalBytes, now, message);
    }

    /**
     * 恢复前只读 Dry-Run 冲突预览（v6.4.0 D1）：
     * 基于 RESP Pipeline 批量 EXISTS 统计将被覆盖的冲突键、新增键与数据类型分布，零写操作。
     */
    public BackupService.RestorePreviewView previewRestore(Long recordId) {
        BackupRecord record = repository.findById(recordId)
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在：" + recordId));
        if (record.getStatus() != BackupStatus.SUCCESS) {
            throw BusinessException.badRequest("仅成功的备份可预览恢复");
        }
        DbInstance instance = instanceService.require(record.getInstanceId());
        String fileName = record.getFileName();
        storageBackend.validateFileName(fileName);
        if (!storageBackend.exists(fileName)) {
            throw BusinessException.notFound("备份文件已丢失：" + fileName);
        }

        long totalKeys = 0L;
        long conflictKeys = 0L;
        long newKeys = 0L;
        long expiredKeys = 0L;
        Map<String, Long> typeBreakdown = new LinkedHashMap<>();
        List<String> sampleConflicts = new ArrayList<>(MAX_SAMPLE_CONFLICTS);
        List<String> batchKeys = new ArrayList<>(BackupSnapshotSupport.PIPELINE_BATCH_SIZE);

        try (BufferedReader reader = BackupSnapshotSupport.openSnapshotReader(storageBackend, fileName)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                BackupService.SnapshotEntry entry = objectMapper.readValue(line, BackupService.SnapshotEntry.class);
                totalKeys++;
                if (entry.key() == null || entry.value() == null || entry.ttl() == -2L) {
                    expiredKeys++;
                    continue;
                }
                String type = entry.type() == null || entry.type().isBlank() ? "unknown" : entry.type();
                typeBreakdown.merge(type, 1L, Long::sum);
                batchKeys.add(entry.key());
                if (batchKeys.size() >= BackupSnapshotSupport.PIPELINE_BATCH_SIZE) {
                    long conflicts = probeConflictsBatch(instance, batchKeys, sampleConflicts);
                    conflictKeys += conflicts;
                    newKeys += batchKeys.size() - conflicts;
                    batchKeys.clear();
                }
            }
            if (!batchKeys.isEmpty()) {
                long conflicts = probeConflictsBatch(instance, batchKeys, sampleConflicts);
                conflictKeys += conflicts;
                newKeys += batchKeys.size() - conflicts;
                batchKeys.clear();
            }
        } catch (IOException | RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new BackupService.RestorePreviewView(
                    record.getId(), fileName, record.getVerifyStatus(), false,
                    totalKeys, conflictKeys, newKeys, expiredKeys,
                    typeBreakdown, sampleConflicts,
                    BackupSnapshotSupport.truncate("归档读取或解析失败，无法预览：" + reason));
        }

        String warning = null;
        if (record.getVerifyStatus() == BackupVerifyStatus.CORRUPTED) {
            warning = "该归档完整性状态为 CORRUPTED，强烈建议重新校验或更换正常备份后再恢复";
        } else if (conflictKeys > 0) {
            warning = "目标实例已存在 " + conflictKeys + " 个同名键将被覆盖，建议勾选「恢复前自动创建保护快照」";
        }
        return new BackupService.RestorePreviewView(
                record.getId(), fileName, record.getVerifyStatus(), true,
                totalKeys, conflictKeys, newKeys, expiredKeys,
                typeBreakdown, sampleConflicts, warning);
    }

    private long probeConflictsBatch(DbInstance instance, List<String> keys,
                                     List<String> sampleConflicts) throws IOException {
        List<List<byte[]>> commands = new ArrayList<>(keys.size());
        for (String key : keys) {
            commands.add(RespClient.commandBytes("EXISTS", key));
        }
        List<RespReply> replies = gateway.executeBatch(instance, commands);
        if (replies.size() != keys.size()) {
            throw new IOException("管线 EXISTS 预览返回条目数不匹配：期望 "
                    + keys.size() + "，实际 " + replies.size());
        }
        long conflicts = 0L;
        for (int i = 0; i < keys.size(); i++) {
            RespReply reply = replies.get(i);
            if (reply instanceof RespReply.Integer integer && integer.value() > 0L) {
                conflicts++;
                if (sampleConflicts.size() < MAX_SAMPLE_CONFLICTS) {
                    sampleConflicts.add(keys.get(i));
                }
            }
        }
        return conflicts;
    }

    private long validateArchiveEntries(InputStream nonClosingTarget) throws IOException {
        long validEntries = 0L;
        PushbackInputStream pb = new PushbackInputStream(
                new BackupSnapshotSupport.NonClosingInputStream(nonClosingTarget), 2);
        byte[] header = new byte[2];
        int read = pb.read(header);
        if (read > 0) {
            pb.unread(header, 0, read);
        }
        InputStream decoded = BackupSnapshotSupport.isGzipHeader(header, read)
                ? new GZIPInputStream(pb, 8192)
                : pb;
        try (InputStream stream = decoded;
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                BackupService.SnapshotEntry entry = objectMapper.readValue(line, BackupService.SnapshotEntry.class);
                if (entry.key() == null || entry.type() == null || entry.type().isBlank()) {
                    throw new IOException("归档条目缺少必填字段 key/type");
                }
                validEntries++;
            }
        }
        return validEntries;
    }
}
