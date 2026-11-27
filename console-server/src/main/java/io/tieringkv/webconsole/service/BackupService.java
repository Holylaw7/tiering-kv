package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.BackupVerifyStatus;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 逻辑备份与物理快照统一门面（Wave 3B M2）：
 * 负责实例级并发互斥（本地锁 + 跨副本 DB 租约锁）、任务状态占位、异步调度与下载入口；
 * 流式导出、管线恢复、完整性校验/干跑预览及物理 Checkpoint 委派至四个单一职责协作 Bean（严格单向依赖，零循环）。
 */
@Service
public class BackupService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long STALE_RUNNING_MINUTES = 30;
    private static final long STALE_RESTORING_MINUTES = 30;
    private static final Duration INSTANCE_OP_LEASE_TTL = Duration.ofSeconds(15);

    private final BackupRecordRepository repository;
    private final InstanceService instanceService;
    private final ConsoleProperties properties;
    private final BackupStorageBackend storageBackend;
    private final Executor executor;
    private final AuditService auditService;
    private final BackupCapacityService capacityService;
    private final DbLeaseLockService leaseLockService;
    private final BackupExportExecutor exportExecutor;
    private final BackupRestoreExecutor restoreExecutor;
    private final BackupVerificationService verificationService;
    private final BackupCheckpointService checkpointService;

    /**
     * 同一实例的“检查 + 落库”本地快速互斥锁（TD-101）；跨副本临界区由 {@link DbLeaseLockService} 兜底。
     */
    private final ConcurrentHashMap<Long, Object> instanceLocks = new ConcurrentHashMap<>();

    public BackupService(BackupRecordRepository repository, InstanceService instanceService,
                         KeyspaceService keyspaceService, RespGateway gateway,
                         ObjectMapper objectMapper, ConsoleProperties properties,
                         @Qualifier("backupExecutor") Executor executor,
                         AuditService auditService,
                         BackupCapacityService capacityService) {
        this(repository, instanceService, keyspaceService, gateway, objectMapper, properties,
                new LocalBackupStorageBackend(properties),
                executor, auditService, capacityService, null);
    }

    public BackupService(BackupRecordRepository repository, InstanceService instanceService,
                         KeyspaceService keyspaceService, RespGateway gateway,
                         ObjectMapper objectMapper, ConsoleProperties properties,
                         BackupStorageBackend storageBackend,
                         @Qualifier("backupExecutor") Executor executor,
                         AuditService auditService,
                         BackupCapacityService capacityService) {
        this(repository, instanceService, keyspaceService, gateway, objectMapper, properties,
                storageBackend, executor, auditService, capacityService, null);
    }

    public BackupService(BackupRecordRepository repository, InstanceService instanceService,
                         KeyspaceService keyspaceService, RespGateway gateway,
                         ObjectMapper objectMapper, ConsoleProperties properties,
                         BackupStorageBackend storageBackend,
                         @Qualifier("backupExecutor") Executor executor,
                         AuditService auditService,
                         BackupCapacityService capacityService,
                         DbLeaseLockService leaseLockService) {
        this(repository, instanceService, properties, storageBackend, executor, auditService,
                capacityService, leaseLockService,
                new BackupExportExecutor(repository, instanceService, keyspaceService, gateway,
                        objectMapper, properties, storageBackend, capacityService),
                new BackupRestoreExecutor(repository, instanceService, gateway,
                        objectMapper, storageBackend, auditService),
                new BackupVerificationService(repository, instanceService, gateway,
                        objectMapper, storageBackend, auditService),
                new BackupCheckpointService(instanceService, gateway, auditService));
    }

    @Autowired
    public BackupService(BackupRecordRepository repository,
                         InstanceService instanceService,
                         ConsoleProperties properties,
                         BackupStorageBackend storageBackend,
                         @Qualifier("backupExecutor") Executor executor,
                         AuditService auditService,
                         BackupCapacityService capacityService,
                         DbLeaseLockService leaseLockService,
                         BackupExportExecutor exportExecutor,
                         BackupRestoreExecutor restoreExecutor,
                         BackupVerificationService verificationService,
                         BackupCheckpointService checkpointService) {
        this.repository = repository;
        this.instanceService = instanceService;
        this.properties = properties;
        this.storageBackend = storageBackend;
        this.executor = executor;
        this.auditService = auditService;
        this.capacityService = capacityService;
        this.leaseLockService = leaseLockService;
        this.exportExecutor = exportExecutor;
        this.restoreExecutor = restoreExecutor;
        this.verificationService = verificationService;
        this.checkpointService = checkpointService;
    }

    @Autowired(required = false)
    void setMetricsBinder(ConsoleMetricsBinder metricsBinder) {
        this.exportExecutor.setMetricsBinder(metricsBinder);
        this.restoreExecutor.setMetricsBinder(metricsBinder);
    }

    /** 备份条目（JSONL 一行）。 */
    public record SnapshotEntry(String key, String type, long ttl, Object value) {
    }

    /** 流式下载句柄（兼容本地磁盘与 S3/MinIO 对象存储）。 */
    public record DownloadStream(String fileName, InputStream inputStream) {
    }

    /** 物理快照触发响应（N1-B）。 */
    public record CheckpointResult(boolean triggered, String message, long lastSaveTime) {
    }

    /** 物理快照状态视图（N1-B：来自 LASTSAVE 与 INFO persistence）。 */
    public record CheckpointStatusView(
            boolean supported,
            boolean inProgress,
            long lastSaveTime,
            String lastStatus,
            long lastDurationSeconds,
            long lastDurationMillis,
            String lastError) {
    }

    /** 归档完整性校验结果（v6.4.0 D1）。 */
    public record BackupVerifyResult(
            Long backupId,
            String fileName,
            BackupVerifyStatus status,
            String expectedSha256,
            String actualSha256,
            long validEntries,
            long sizeBytes,
            LocalDateTime verifiedAt,
            String message) {
    }

    /** 恢复前只读 Dry-Run 冲突预览视图（v6.4.0 D1）。 */
    public record RestorePreviewView(
            Long backupId,
            String fileName,
            BackupVerifyStatus verifyStatus,
            boolean archiveReadable,
            long totalKeys,
            long conflictKeys,
            long newKeys,
            long expiredKeys,
            Map<String, Long> typeBreakdown,
            List<String> sampleConflicts,
            String warning) {
        public RestorePreviewView {
            typeBreakdown = typeBreakdown == null ? Map.of() : Map.copyOf(typeBreakdown);
            sampleConflicts = sampleConflicts == null ? List.of() : List.copyOf(sampleConflicts);
        }
    }

    /** 安全恢复请求体（v6.4.0 D1：可选开启恢复前自动保护快照）。 */
    public record RestoreRequest(boolean createSafetySnapshot) {
    }

    public List<BackupRecord> list(Long instanceId) {
        return instanceId == null
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findByInstanceIdOrderByCreatedAtDesc(instanceId);
    }

    /** 清理已删除实例的本地锁条目，防止实例长期增删产生内存泄漏（Wave 2A H1/H4）。 */
    public void evictInstanceLock(Long instanceId) {
        if (instanceId != null) {
            instanceLocks.remove(instanceId);
        }
    }

    /** 跨副本对账：移除数据库中已不存在的实例本地锁对象。 */
    public int reconcileInstanceLocks(Set<Long> activeIds) {
        int removed = 0;
        for (Long id : List.copyOf(instanceLocks.keySet())) {
            if (!activeIds.contains(id) && instanceLocks.remove(id) != null) {
                removed++;
            }
        }
        return removed;
    }

    private static String instanceLeaseKey(Long instanceId) {
        return "backup-op:instance:" + instanceId;
    }

    /** 提交一次异步逻辑导出（TD-091 / TD-101）。 */
    public BackupRecord export(Long instanceId) {
        instanceService.require(instanceId);
        Object lock = instanceLocks.computeIfAbsent(instanceId, key -> new Object());
        BackupRecord created;
        synchronized (lock) {
            String leaseKey = instanceLeaseKey(instanceId);
            boolean leaseHeld = acquireInstanceLeaseOrConflict(leaseKey);
            try {
                // 恢复进行中禁止导出：此时导出的是一份"半恢复"的数据（ADR-0366）
                repository.findFirstByInstanceIdAndRestoreStatusOrderByRestoreStartedAtDesc(
                                instanceId, RestoreStatus.RESTORING)
                        .ifPresent(restoring -> {
                            if (BackupSnapshotSupport.isFresh(
                                    restoring.getRestoreStartedAt(), STALE_RESTORING_MINUTES)) {
                                throw BusinessException.conflict(
                                        "该实例正在恢复（备份 id=" + restoring.getId() + "），请稍后再试");
                            }
                            restoring.setRestoreStatus(RestoreStatus.FAILED);
                            restoring.setRestoreMessage("恢复超时未完成，已标记为失败");
                            restoring.setRestoreFinishedAt(LocalDateTime.now());
                            repository.save(restoring);
                        });
                repository.findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(instanceId, BackupStatus.RUNNING)
                        .ifPresent(running -> {
                            if (running.getCreatedAt() != null
                                    && running.getCreatedAt().isAfter(
                                            LocalDateTime.now().minusMinutes(STALE_RUNNING_MINUTES))) {
                                throw BusinessException.conflict(
                                        "该实例已有备份任务在执行（id=" + running.getId() + "），请稍后再试");
                            }
                            running.setStatus(BackupStatus.FAILED);
                            running.setMessage("任务超时未完成，已标记为失败");
                            running.setFinishedAt(LocalDateTime.now());
                            repository.save(running);
                        });

                BackupRecord record = new BackupRecord();
                record.setInstanceId(instanceId);
                String unique = UUID.randomUUID().toString().substring(0, 8);
                String ext = properties.getBackup().isCompress() ? ".jsonl.gz" : ".jsonl";
                record.setFileName("backup-" + instanceId + "-" + LocalDateTime.now().format(STAMP) + "-" + unique + ext);
                record.setStatus(BackupStatus.RUNNING);
                record.setProcessedKeys(0L);
                record.setCreatedAt(LocalDateTime.now());
                created = repository.save(record);
            } finally {
                if (leaseHeld) {
                    leaseLockService.release(leaseKey);
                }
            }
        }

        Long recordId = created.getId();
        try {
            capacityService.reserve(instanceId, recordId, capacityService.estimate(instanceId));
        } catch (RuntimeException ex) {
            created.setStatus(BackupStatus.FAILED);
            created.setMessage(BackupSnapshotSupport.truncate(ex.getMessage()));
            created.setFinishedAt(LocalDateTime.now());
            repository.save(created);
            throw ex;
        }
        // 备份是可恢复数据的敏感操作，必须留痕（Phase 11 P1）
        auditService.record("CREATE_BACKUP",
                BackupSnapshotSupport.instanceName(instanceService, instanceId), created.getFileName(), true);
        executor.execute(() -> runExport(recordId));
        return created;
    }

    /** 异步执行体：可单独调用以便测试与重试。 */
    public void runExport(Long recordId) {
        exportExecutor.runExport(recordId);
    }

    /** 提交一次异步恢复任务（ADR-0366 + O1-B）。 */
    public BackupRecord restore(Long recordId) {
        return restore(recordId, false);
    }

    /** 提交一次异步恢复任务，可选先在目标实例创建预恢复安全保护快照（v6.4.0 D1）。 */
    public BackupRecord restore(Long recordId, boolean createSafetySnapshot) {
        BackupRecord record = repository.findById(recordId)
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在：" + recordId));
        if (record.getStatus() != BackupStatus.SUCCESS) {
            throw BusinessException.badRequest("仅成功的备份可恢复");
        }
        instanceService.require(record.getInstanceId());
        storageBackend.validateFileName(record.getFileName());
        if (!storageBackend.exists(record.getFileName())) {
            throw BusinessException.notFound("备份文件已丢失：" + record.getFileName());
        }

        Long instanceId = record.getInstanceId();
        Object lock = instanceLocks.computeIfAbsent(instanceId, key -> new Object());
        BackupRecord saved;
        BackupRecord safetyRecord = null;
        synchronized (lock) {
            String leaseKey = instanceLeaseKey(instanceId);
            boolean leaseHeld = acquireInstanceLeaseOrConflict(leaseKey);
            try {
                // 恢复进行中：拒绝第二次恢复
                repository.findFirstByInstanceIdAndRestoreStatusOrderByRestoreStartedAtDesc(
                                instanceId, RestoreStatus.RESTORING)
                        .ifPresent(restoring -> {
                            if (BackupSnapshotSupport.isFresh(
                                    restoring.getRestoreStartedAt(), STALE_RESTORING_MINUTES)) {
                                throw BusinessException.conflict(
                                        "该实例正在恢复（备份 id=" + restoring.getId() + "），请稍后再试");
                            }
                            restoring.setRestoreStatus(RestoreStatus.FAILED);
                            restoring.setRestoreMessage("恢复超时未完成，已标记为失败");
                            restoring.setRestoreFinishedAt(LocalDateTime.now());
                            repository.save(restoring);
                        });
                // 备份进行中：恢复会与导出争用同一实例，返回 409
                repository.findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(instanceId, BackupStatus.RUNNING)
                        .ifPresent(running -> {
                            if (BackupSnapshotSupport.isFresh(running.getCreatedAt(), STALE_RUNNING_MINUTES)) {
                                throw BusinessException.conflict(
                                        "该实例正在备份（id=" + running.getId() + "），请稍后再试");
                            }
                        });
                if (createSafetySnapshot) {
                    BackupRecord safety = new BackupRecord();
                    safety.setInstanceId(instanceId);
                    String unique = UUID.randomUUID().toString().substring(0, 8);
                    String ext = properties.getBackup().isCompress() ? ".jsonl.gz" : ".jsonl";
                    safety.setFileName("pre-restore-" + instanceId + "-"
                            + LocalDateTime.now().format(STAMP) + "-" + unique + ext);
                    safety.setStatus(BackupStatus.RUNNING);
                    safety.setProcessedKeys(0L);
                    safety.setMessage("恢复 #" + recordId + " 前自动保护快照");
                    safety.setCreatedAt(LocalDateTime.now());
                    safetyRecord = repository.save(safety);
                }
                // 标记进入恢复：先落库再提交异步执行，保证其他请求立即看到 RESTORING
                record.setRestoreStatus(RestoreStatus.RESTORING);
                record.setRestoreStartedAt(LocalDateTime.now());
                record.setRestoreFinishedAt(null);
                record.setRestoredKeys(0);
                record.setPreRestoreBackupId(safetyRecord != null ? safetyRecord.getId() : null);
                record.setRestoreMessage(safetyRecord != null
                        ? "正在生成恢复前保护快照 #" + safetyRecord.getId()
                        : null);
                saved = repository.save(record);
            } finally {
                if (leaseHeld) {
                    leaseLockService.release(leaseKey);
                }
            }
        }

        if (safetyRecord != null) {
            Long safetyId = safetyRecord.getId();
            try {
                capacityService.reserve(instanceId, safetyId, capacityService.estimate(instanceId));
            } catch (RuntimeException ex) {
                safetyRecord.setStatus(BackupStatus.FAILED);
                safetyRecord.setMessage(BackupSnapshotSupport.truncate(ex.getMessage()));
                safetyRecord.setFinishedAt(LocalDateTime.now());
                repository.save(safetyRecord);
                restoreExecutor.finishRestore(saved, RestoreStatus.FAILED, 0L,
                        BackupSnapshotSupport.truncate("恢复前保护快照容量预留失败：" + ex.getMessage()));
                throw ex;
            }
            auditService.record("CREATE_SAFETY_SNAPSHOT",
                    BackupSnapshotSupport.instanceName(instanceService, instanceId),
                    "beforeRestore=" + recordId + " file=" + safetyRecord.getFileName(), true);
            executor.execute(() -> runSafetySnapshotThenRestore(safetyId, recordId, instanceId, record.getFileName()));
        } else {
            executor.execute(() -> runRestore(recordId));
        }
        return saved;
    }

    private void runSafetySnapshotThenRestore(Long safetyId, Long targetRecordId,
                                              Long instanceId, String targetFileName) {
        runExport(safetyId);
        BackupRecord finishedSafety = repository.findById(safetyId).orElse(null);
        if (finishedSafety == null || finishedSafety.getStatus() != BackupStatus.SUCCESS) {
            BackupRecord target = repository.findById(targetRecordId).orElse(null);
            if (target != null) {
                String reason = finishedSafety != null && finishedSafety.getMessage() != null
                        ? finishedSafety.getMessage()
                        : "未知原因";
                restoreExecutor.finishRestore(target, RestoreStatus.FAILED, 0L,
                        BackupSnapshotSupport.truncate("恢复前保护快照创建失败，已中止恢复：" + reason));
            }
            auditService.record("RESTORE_BACKUP",
                    BackupSnapshotSupport.instanceName(instanceService, instanceId),
                    "file=" + targetFileName + " aborted=safety_snapshot_failed", false);
            return;
        }
        runRestore(targetRecordId);
    }

    /** 校验逻辑备份归档的 SHA-256 摘要与 Gzip/JSONL 结构完整性（v6.4.0 D1）。 */
    public BackupVerifyResult verify(Long recordId) {
        return verificationService.verify(recordId);
    }

    /** 恢复前只读 Dry-Run 冲突预览（v6.4.0 D1）。 */
    public RestorePreviewView previewRestore(Long recordId) {
        return verificationService.previewRestore(recordId);
    }

    /** 恢复异步执行体（O1-B + O2-A）。 */
    public long runRestore(Long recordId) {
        return restoreExecutor.runRestore(recordId);
    }

    /** 流式下载入口（Phase 11 P1 + N1-A）：先校验并打开输入流，再写审计留痕。 */
    public DownloadStream openDownload(Long recordId) {
        BackupRecord record = repository.findById(recordId)
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在：" + recordId));
        storageBackend.validateFileName(record.getFileName());
        if (!storageBackend.exists(record.getFileName())) {
            throw BusinessException.notFound("备份文件不存在：" + record.getFileName());
        }
        InputStream stream;
        try {
            stream = storageBackend.openInput(record.getFileName());
        } catch (IOException ex) {
            throw BusinessException.upstream("读取备份文件失败：" + ex.getMessage());
        }
        String target = BackupSnapshotSupport.instanceName(instanceService, record.getInstanceId());
        auditService.record("DOWNLOAD_BACKUP", target, record.getFileName(), true);
        return new DownloadStream(record.getFileName(), stream);
    }

    /** 触发数据面异步物理快照 BGSAVE（WAL + Cold SSTable + 向量索引），结果记入审计。 */
    public CheckpointResult triggerCheckpoint(Long instanceId) {
        return checkpointService.triggerCheckpoint(instanceId);
    }

    /** 查询实例最近一次物理快照状态（LASTSAVE + INFO persistence），兼容旧版数据面降级。 */
    public CheckpointStatusView checkpointStatus(Long instanceId) {
        return checkpointService.checkpointStatus(instanceId);
    }

    private boolean acquireInstanceLeaseOrConflict(String leaseKey) {
        if (leaseLockService == null) {
            return false;
        }
        if (!leaseLockService.tryAcquire(leaseKey, INSTANCE_OP_LEASE_TTL)) {
            throw BusinessException.conflict("该实例正在提交备份或恢复任务，请稍后再试");
        }
        return true;
    }
}
