package io.tieringkv.webconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.BackupReservationRepository;
import io.tieringkv.webconsole.resp.RespClient;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.support.InfoParser;
import io.tieringkv.webconsole.support.Text;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 逻辑备份：SCAN 流式导出（支持 .jsonl.gz 压缩与可配置键数），按类型幂等回放恢复。 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BackupRecordRepository repository;
    private final InstanceService instanceService;
    private final KeyspaceService keyspaceService;
    private final RespGateway gateway;
    private final ObjectMapper objectMapper;
    private final ConsoleProperties properties;
    private final BackupStorageBackend storageBackend;
    private final Executor executor;
    private final AuditService auditService;
    private final BackupCapacityService capacityService;
    private final DbLeaseLockService leaseLockService;

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

    @Autowired
    public BackupService(BackupRecordRepository repository, InstanceService instanceService,
                         KeyspaceService keyspaceService, RespGateway gateway,
                         ObjectMapper objectMapper, ConsoleProperties properties,
                         BackupStorageBackend storageBackend,
                         @Qualifier("backupExecutor") Executor executor,
                         AuditService auditService,
                         BackupCapacityService capacityService,
                         DbLeaseLockService leaseLockService) {
        this.repository = repository;
        this.instanceService = instanceService;
        this.keyspaceService = keyspaceService;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.storageBackend = storageBackend;
        this.executor = executor;
        this.auditService = auditService;
        this.capacityService = capacityService;
        this.leaseLockService = leaseLockService;
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

    public List<BackupRecord> list(Long instanceId) {
        return instanceId == null
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findByInstanceIdOrderByCreatedAtDesc(instanceId);
    }

    /** 并发备份冲突时返回 409（TD-091）。 */
    private static final long STALE_RUNNING_MINUTES = 30;

    /**
     * 提交一次逻辑导出（TD-091）：
     * <ul>
     *   <li>同一实例已有 RUNNING 记录 → {@code 409}；超过 30 分钟的僵尸任务视为失效；</li>
     *   <li>立即返回 RUNNING 记录，实际导出在 {@code backupExecutor} 中异步执行；</li>
     *   <li>前端轮询 {@code GET /backups} 观察状态变化。</li>
     * </ul>
     */
    /**
     * 同一实例的"检查 + 落库"必须原子（TD-101）。
     *
     * <p>此前两个同时到达的请求都会读到"无 RUNNING 记录"，双双返回 200，
     * 导致同一实例并行跑两个导出任务（Phase 10 实测 5/5 轮复现）。
     * 这里按实例加锁，把检查与插入合并为一个临界区；导出仍在锁外提交给线程池。
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> instanceLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 进度持久化节流阈值：每处理 500 键或间隔 ≥ 1 秒刷库一次，避免高频写库。 */
    private static final long PROGRESS_FLUSH_KEYS = 500L;
    private static final long PROGRESS_FLUSH_MS = 1_000L;

    /** RESP Pipeline 导出/恢复每批键数上限（O2-A），在吞吐与单批内存占用间平衡。 */
    private static final int PIPELINE_BATCH_SIZE = 100;

    /** 恢复超过该时长仍未结束，视为进程崩溃遗留的僵尸状态（ADR-0366）。 */
    private static final long STALE_RESTORING_MINUTES = 30;
    private static final Duration INSTANCE_OP_LEASE_TTL = Duration.ofSeconds(15);

    private static String instanceLeaseKey(Long instanceId) {
        return "backup-op:instance:" + instanceId;
    }

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
                            if (isFresh(restoring.getRestoreStartedAt(), STALE_RESTORING_MINUTES)) {
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
                String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
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
            created.setMessage(truncate(ex.getMessage()));
            created.setFinishedAt(LocalDateTime.now());
            repository.save(created);
            throw ex;
        }
        // 备份是可恢复数据的敏感操作，必须留痕（Phase 11 P1）
        auditService.record("CREATE_BACKUP", instanceName(instanceId), created.getFileName(), true);
        executor.execute(() -> runExport(recordId));
        return created;
    }

    /** 异步执行体：可单独调用以便测试与重试。 */
    public void runExport(Long recordId) {
        BackupRecord record = repository.findById(recordId).orElse(null);
        if (record == null) {
            return;
        }
        DbInstance instance = instanceService.require(record.getInstanceId());
        String fileName = record.getFileName();
        long written = 0;
        try {
            long keys = writeSnapshot(instance, record, fileName);
            written = storageBackend.publish(fileName);
            record.setKeyCount(keys);
            record.setProcessedKeys(keys);
            record.setSizeBytes(written);
            record.setStatus(BackupStatus.SUCCESS);
            record.setMessage("导出 " + keys + " 个键");
        } catch (IOException | RuntimeException ex) {
            // 磁盘写满、只读、进程级异常：临时文件必须清掉，记录必须失败
            storageBackend.deleteTempQuietly(fileName);
            record.setStatus(BackupStatus.FAILED);
            record.setMessage(truncate(ex.getMessage()));
            log.error("备份失败 instance={}", instance.getName(), ex);
        } finally {
            capacityService.release(recordId, written);
        }
        record.setFinishedAt(LocalDateTime.now());
        repository.save(record);
    }

    private long writeSnapshot(DbInstance instance, BackupRecord record, String fileName) throws IOException {
        long count = 0;
        long lastProgressFlushAt = System.currentTimeMillis();
        String cursor = "0";
        int scanCount = Math.max(50, properties.getBackup().getScanCount());
        long maxKeys = properties.getBackup().getMaxKeys();
        boolean compress = fileName.endsWith(".gz");
        try (OutputStream rawOut = storageBackend.openTempOutput(fileName);
             OutputStream dataOut = compress ? new GZIPOutputStream(rawOut, 8192) : rawOut;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(dataOut, StandardCharsets.UTF_8))) {
            do {
                RespReply reply = gateway.execute(instance, "SCAN", cursor, "MATCH", "*",
                        "COUNT", String.valueOf(scanCount));
                if (reply instanceof RespReply.Error error) {
                    throw new IOException("SCAN 失败：" + error.message());
                }
                if (!(reply instanceof RespReply.Array array) || array.items().size() < 2) {
                    throw new IOException("SCAN 返回结构异常");
                }
                cursor = text(array.items().get(0));
                if (array.items().get(1) instanceof RespReply.Array keyArray && !keyArray.isNil()) {
                    List<String> pageKeys = new ArrayList<>(keyArray.items().size());
                    for (RespReply item : keyArray.items()) {
                        String key = Text.decode(
                                item instanceof RespReply.Bulk bulk ? bulk.value() : null);
                        if (key != null) {
                            pageKeys.add(key);
                        }
                    }
                    for (int offset = 0; offset < pageKeys.size(); offset += PIPELINE_BATCH_SIZE) {
                        int end = Math.min(offset + PIPELINE_BATCH_SIZE, pageKeys.size());
                        List<String> batchKeys = pageKeys.subList(offset, end);
                        List<KeyEntry> metas = keyspaceService.inspectBatch(instance, batchKeys);
                        List<Object> values = keyspaceService.readValuesBatch(instance, metas);
                        for (int i = 0; i < metas.size(); i++) {
                            KeyEntry meta = metas.get(i);
                            if ("none".equals(meta.type())) {
                                continue;
                            }
                            Object val = i < values.size() ? values.get(i) : null;
                            writer.write(objectMapper.writeValueAsString(
                                    new SnapshotEntry(meta.key(), meta.type(), meta.ttl(), val)));
                            writer.newLine();
                            count++;
                            if (maxKeys > 0 && count >= maxKeys) {
                                throw new IllegalStateException("备份键数超过配置上限 " + maxKeys + "，已中止以避免生成不完整备份");
                            }
                        }
                        long now = System.currentTimeMillis();
                        if (count > 0 && (count % PROGRESS_FLUSH_KEYS < PIPELINE_BATCH_SIZE
                                || (now - lastProgressFlushAt) >= PROGRESS_FLUSH_MS)) {
                            record.setProcessedKeys(count);
                            repository.save(record);
                            lastProgressFlushAt = now;
                        }
                    }
                }
            } while (!"0".equals(cursor));
        }
        return count;
    }

    private String instanceName(Long instanceId) {
        try {
            return instanceService.require(instanceId).getName();
        } catch (RuntimeException ex) {
            return "instance#" + instanceId;
        }
    }

    /**
     * 提交一次异步恢复任务（ADR-0366 + O1-B）。
     *
     * <p>四道约束：
     * <ol>
     *   <li><b>实例级互斥</b>：与导出共用同一把按实例锁，且恢复期间导出、另一次恢复均返回 409；</li>
     *   <li><b>异步防超时</b>：锁内落库 {@link RestoreStatus#RESTORING} 后立即返回记录，
     *       实际回放提交至 {@code backupExecutor}，避免大库恢复击穿 HTTP 超时；</li>
     *   <li><b>状态隔离</b>：恢复状态写入 {@code restoreStatus}，不污染备份记录自身的 {@code status}；</li>
     *   <li><b>进度与异常安全</b>：按批节流更新 {@code restoredKeys}，失败时标记 {@link RestoreStatus#FAILED}
     *       并保留部分已回放键数，绝不把失败标成 SUCCEEDED。</li>
     * </ol>
     */
    public BackupRecord restore(Long recordId) {
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
        synchronized (lock) {
            String leaseKey = instanceLeaseKey(instanceId);
            boolean leaseHeld = acquireInstanceLeaseOrConflict(leaseKey);
            try {
                // 恢复进行中：拒绝第二次恢复
                repository.findFirstByInstanceIdAndRestoreStatusOrderByRestoreStartedAtDesc(
                                instanceId, RestoreStatus.RESTORING)
                        .ifPresent(restoring -> {
                            if (isFresh(restoring.getRestoreStartedAt(), STALE_RESTORING_MINUTES)) {
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
                            if (isFresh(running.getCreatedAt(), STALE_RUNNING_MINUTES)) {
                                throw BusinessException.conflict(
                                        "该实例正在备份（id=" + running.getId() + "），请稍后再试");
                            }
                        });
                // 标记进入恢复：先落库再提交异步执行，保证其他请求立即看到 RESTORING
                record.setRestoreStatus(RestoreStatus.RESTORING);
                record.setRestoreStartedAt(LocalDateTime.now());
                record.setRestoreFinishedAt(null);
                record.setRestoredKeys(0);
                record.setRestoreMessage(null);
                saved = repository.save(record);
            } finally {
                if (leaseHeld) {
                    leaseLockService.release(leaseKey);
                }
            }
        }

        executor.execute(() -> runRestore(recordId));
        return saved;
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

    /** 恢复异步执行体（O1-B + O2-A）：使用 RESP Pipeline 批回放并按节流刷新 restoredKeys。 */
    public long runRestore(Long recordId) {
        BackupRecord record = repository.findById(recordId).orElse(null);
        if (record == null) {
            return 0L;
        }
        DbInstance instance = instanceService.require(record.getInstanceId());
        long restored = 0;
        long lastProgressFlushAt = System.currentTimeMillis();
        long lastFlushedCount = 0;
        List<SnapshotEntry> batch = new ArrayList<>(PIPELINE_BATCH_SIZE);
        try (BufferedReader reader = openSnapshotReader(record.getFileName())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                SnapshotEntry entry;
                try {
                    entry = objectMapper.readValue(line, SnapshotEntry.class);
                } catch (Exception parseEx) {
                    // 先提交此前已解析合法批次，保证部分恢复计数与文件行顺序一致
                    if (!batch.isEmpty()) {
                        restored += restoreBatch(instance, batch);
                        batch.clear();
                    }
                    throw parseEx;
                }
                batch.add(entry);
                if (batch.size() >= PIPELINE_BATCH_SIZE) {
                    restored += restoreBatch(instance, batch);
                    batch.clear();
                    long now = System.currentTimeMillis();
                    if ((restored - lastFlushedCount) >= PROGRESS_FLUSH_KEYS
                            || (now - lastProgressFlushAt) >= PROGRESS_FLUSH_MS) {
                        record.setRestoredKeys(restored);
                        repository.save(record);
                        lastFlushedCount = restored;
                        lastProgressFlushAt = now;
                    }
                }
            }
            if (!batch.isEmpty()) {
                restored += restoreBatch(instance, batch);
                batch.clear();
            }
            finishRestore(record, RestoreStatus.SUCCEEDED, restored, null);
            auditService.record("RESTORE_BACKUP", instance.getName(),
                    "file=" + record.getFileName() + " restored=" + restored, true);
            return restored;
        } catch (BatchRestoreFailure failure) {
            restored += failure.succeededInBatch();
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            finishRestore(record, RestoreStatus.FAILED, restored,
                    truncate("已回放 " + restored + " 个键，恢复中断：" + reason));
            auditService.record("RESTORE_BACKUP", instance.getName(),
                    "file=" + record.getFileName() + " restored=" + restored + " result=failed", false);
            log.error("恢复批次失败 recordId={} instance={} restored={}", recordId, instance.getName(), restored, failure);
            return restored;
        } catch (Exception ex) {
            // 异常安全：已写入的部分保留，状态明确标失败，并记录"恢复了多少"
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            finishRestore(record, RestoreStatus.FAILED, restored,
                    truncate("已回放 " + restored + " 个键，恢复中断：" + reason));
            auditService.record("RESTORE_BACKUP", instance.getName(),
                    "file=" + record.getFileName() + " restored=" + restored + " result=failed", false);
            log.error("恢复失败 recordId={} instance={} restored={}", recordId, instance.getName(), restored, ex);
            return restored;
        }
    }

    private static final class BatchRestoreFailure extends IOException {
        private final long succeededInBatch;

        BatchRestoreFailure(long succeededInBatch, String message) {
            super(message);
            this.succeededInBatch = succeededInBatch;
        }

        long succeededInBatch() {
            return succeededInBatch;
        }
    }

    /** 批次管线回放（O2-A）：将多条键恢复命令合并为单次 executeBatch，并按条目顺序核验应答。 */
    private long restoreBatch(DbInstance instance, List<SnapshotEntry> entries) throws IOException {
        List<List<byte[]>> commands = new ArrayList<>(entries.size() * 3);
        List<String> ops = new ArrayList<>(entries.size() * 3);
        int[] entryEndIndex = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            appendRestoreCommands(entries.get(i), commands, ops);
            entryEndIndex[i] = commands.size();
        }
        if (commands.isEmpty()) {
            return entries.size();
        }
        List<RespReply> replies = gateway.executeBatch(instance, commands);
        if (replies.size() != commands.size()) {
            throw new BatchRestoreFailure(0, "管线恢复应答数量不匹配：期望 "
                    + commands.size() + "，实际 " + replies.size());
        }
        long okEntries = 0;
        int start = 0;
        for (int i = 0; i < entries.size(); i++) {
            int end = entryEndIndex[i];
            for (int c = start; c < end; c++) {
                RespReply reply = replies.get(c);
                if (reply instanceof RespReply.Error error) {
                    throw new BatchRestoreFailure(okEntries, ops.get(c) + " 失败：" + error.message());
                }
            }
            okEntries++;
            start = end;
        }
        return okEntries;
    }

    @SuppressWarnings("unchecked")
    private void appendRestoreCommands(SnapshotEntry entry, List<List<byte[]>> commands, List<String> ops)
            throws IOException {
        String key = entry.key();
        if (key == null || entry.value() == null) {
            return;
        }
        commands.add(RespClient.commandBytes("DEL", key));
        ops.add("DEL");
        switch (entry.type()) {
            case "string" -> {
                commands.add(RespClient.commandBytes("SET", key, String.valueOf(entry.value())));
                ops.add("SET");
            }
            case "hash" -> {
                Map<String, String> hash = (Map<String, String>) entry.value();
                List<byte[]> args = new ArrayList<>(2 + hash.size() * 2);
                args.add("HSET".getBytes(StandardCharsets.UTF_8));
                args.add(key.getBytes(StandardCharsets.UTF_8));
                hash.forEach((field, value) -> {
                    args.add(field.getBytes(StandardCharsets.UTF_8));
                    args.add(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                });
                if (args.size() > 2) {
                    commands.add(args);
                    ops.add("HSET");
                }
            }
            case "list" -> {
                List<String> list = (List<String>) entry.value();
                for (String item : list) {
                    commands.add(RespClient.commandBytes("RPUSH", key, item));
                    ops.add("RPUSH");
                }
            }
            case "set" -> {
                List<String> set = (List<String>) entry.value();
                for (String item : set) {
                    commands.add(RespClient.commandBytes("SADD", key, item));
                    ops.add("SADD");
                }
            }
            case "zset" -> {
                List<Map<String, String>> scored = (List<Map<String, String>>) entry.value();
                for (Map<String, String> pair : scored) {
                    commands.add(RespClient.commandBytes("ZADD", key,
                            pair.getOrDefault("score", "0"), pair.getOrDefault("member", "")));
                    ops.add("ZADD");
                }
            }
            case "json" -> {
                String json = entry.value() instanceof String raw
                        ? raw
                        : objectMapper.writeValueAsString(entry.value());
                commands.add(RespClient.commandBytes("JSON.SET", key, "$", json));
                ops.add("JSON.SET");
            }
            default -> log.debug("跳过不支持的类型恢复：{} ({})", key, entry.type());
        }
        if (entry.ttl() > 0) {
            commands.add(RespClient.commandBytes("EXPIRE", key, String.valueOf(entry.ttl())));
            ops.add("EXPIRE");
        }
    }

    /** 写终态并落库；无论成功失败都必须执行，避免实例停留在 RESTORING。 */
    private void finishRestore(BackupRecord record, RestoreStatus status, long restored, String message) {
        record.setRestoreStatus(status);
        record.setRestoreFinishedAt(LocalDateTime.now());
        record.setRestoredKeys(restored);
        record.setRestoreMessage(message);
        repository.save(record);
    }

    private static boolean isFresh(LocalDateTime time, long minutes) {
        return time != null && time.isAfter(LocalDateTime.now().minusMinutes(minutes));
    }

    /**
     * 流式下载入口（Phase 11 P1 + N1-A）：先校验并打开输入流，再写审计留痕。
     */
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
        String target = instanceName(record.getInstanceId());
        auditService.record("DOWNLOAD_BACKUP", target, record.getFileName(), true);
        return new DownloadStream(record.getFileName(), stream);
    }

    /** 触发数据面异步物理快照 BGSAVE（WAL + Cold SSTable + 向量索引），结果记入审计。 */
    public CheckpointResult triggerCheckpoint(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        try {
            RespReply reply = gateway.execute(instance, "BGSAVE");
            if (reply instanceof RespReply.Simple simple) {
                long lastSave = queryLastSaveQuietly(instance);
                auditService.record("BACKUP_CHECKPOINT", instance.getName(), simple.value(), true);
                return new CheckpointResult(true, simple.value(), lastSave);
            }
            if (reply instanceof RespReply.Error error) {
                String msg = error.message() == null ? "" : error.message();
                String lower = msg.toLowerCase(Locale.ROOT);
                auditService.record("BACKUP_CHECKPOINT", instance.getName(), truncate(msg), false);
                if (lower.contains("already in progress")) {
                    return new CheckpointResult(false, msg, queryLastSaveQuietly(instance));
                }
                if (lower.contains("unknown command")) {
                    throw BusinessException.badRequest("目标实例不支持 BGSAVE 命令：" + msg);
                }
                throw BusinessException.upstream("触发物理快照失败：" + msg);
            }
            auditService.record("BACKUP_CHECKPOINT", instance.getName(), "BGSAVE 返回异常响应类型", false);
            throw BusinessException.upstream("BGSAVE 返回异常响应类型");
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            auditService.record("BACKUP_CHECKPOINT", instance.getName(), truncate(ex.getMessage()), false);
            throw BusinessException.upstream("触发 BGSAVE 失败：" + ex.getMessage());
        }
    }

    /** 查询实例最近一次物理快照状态（LASTSAVE + INFO persistence），兼容旧版数据面降级。 */
    public CheckpointStatusView checkpointStatus(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        long lastSave = 0L;
        try {
            RespReply lastReply = gateway.execute(instance, "LASTSAVE");
            if (lastReply instanceof RespReply.Integer integer) {
                lastSave = integer.value();
            } else if (lastReply instanceof RespReply.Error err) {
                String msg = err.message() == null ? "" : err.message();
                if (msg.toLowerCase(Locale.ROOT).contains("unknown command")) {
                    return new CheckpointStatusView(false, false, 0L, "unsupported", -1L, 0L, null);
                }
                throw BusinessException.upstream("查询 LASTSAVE 失败：" + msg);
            }
        } catch (IOException ex) {
            throw BusinessException.upstream("查询 LASTSAVE 失败：" + ex.getMessage());
        }

        boolean inProgress = false;
        String lastStatus = "ok";
        long lastDurationSeconds = -1L;
        long lastDurationMillis = 0L;
        String lastError = null;
        try {
            RespReply infoReply = gateway.execute(instance, "INFO", "persistence");
            if (infoReply instanceof RespReply.Bulk bulk) {
                Map<String, String> section = InfoParser.parse(bulk.utf8())
                        .getOrDefault("Persistence", Map.of());
                if (!section.isEmpty()) {
                    inProgress = "1".equals(section.get("rdb_bgsave_in_progress"));
                    long infoLastSave = parseLongOrDefault(section.get("rdb_last_save_time"), 0L);
                    if (infoLastSave > lastSave) {
                        lastSave = infoLastSave;
                    }
                    lastStatus = section.getOrDefault("rdb_last_bgsave_status", "ok");
                    lastDurationSeconds = parseLongOrDefault(section.get("rdb_last_bgsave_time_sec"), -1L);
                    lastDurationMillis = parseLongOrDefault(section.get("rdb_last_bgsave_duration_ms"), 0L);
                    lastError = section.get("rdb_last_bgsave_error");
                }
            }
        } catch (IOException | RuntimeException ex) {
            log.debug("读取 INFO persistence 失败，保留 LASTSAVE={}", lastSave, ex);
        }

        return new CheckpointStatusView(true, inProgress, lastSave, lastStatus,
                lastDurationSeconds, lastDurationMillis, lastError);
    }

    private long queryLastSaveQuietly(DbInstance instance) {
        try {
            RespReply reply = gateway.execute(instance, "LASTSAVE");
            return reply instanceof RespReply.Integer integer ? integer.value() : 0L;
        } catch (IOException | RuntimeException ex) {
            return 0L;
        }
    }

    private static long parseLongOrDefault(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** 自动识别 GZIP 魔数（0x1F 0x8B），兼容明文 .jsonl 与压缩 .jsonl.gz。 */
    private BufferedReader openSnapshotReader(String fileName) throws IOException {
        InputStream raw = new BufferedInputStream(storageBackend.openInput(fileName));
        PushbackInputStream pb = new PushbackInputStream(raw, 2);
        byte[] header = new byte[2];
        int read = pb.read(header);
        if (read > 0) {
            pb.unread(header, 0, read);
        }
        boolean gzip = read == 2 && (header[0] & 0xff) == 0x1f && (header[1] & 0xff) == 0x8b;
        InputStream decoded = gzip ? new GZIPInputStream(pb, 8192) : pb;
        return new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8));
    }

    private static String text(RespReply reply) {
        return reply instanceof RespReply.Bulk bulk ? Text.decode(bulk.value()) : null;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}