package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespClient;
import io.tieringkv.webconsole.resp.RespReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 逻辑备份流式恢复执行器（Wave 3B M2）：负责 JSONL/GZIP 解析、多数据类型 RESP Pipeline 批回放、
 * 节流进度上报、异常安全终态收敛与审计/指标打点。
 */
@Service
public class BackupRestoreExecutor {

    private static final Logger log = LoggerFactory.getLogger(BackupRestoreExecutor.class);

    private final BackupRecordRepository repository;
    private final InstanceService instanceService;
    private final RespGateway gateway;
    private final ObjectMapper objectMapper;
    private final BackupStorageBackend storageBackend;
    private final AuditService auditService;
    private ConsoleMetricsBinder metricsBinder;

    public BackupRestoreExecutor(BackupRecordRepository repository,
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

    @Autowired(required = false)
    void setMetricsBinder(ConsoleMetricsBinder metricsBinder) {
        this.metricsBinder = metricsBinder;
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
        List<BackupService.SnapshotEntry> batch = new ArrayList<>(BackupSnapshotSupport.PIPELINE_BATCH_SIZE);
        try (BufferedReader reader = BackupSnapshotSupport.openSnapshotReader(storageBackend, record.getFileName())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                BackupService.SnapshotEntry entry;
                try {
                    entry = objectMapper.readValue(line, BackupService.SnapshotEntry.class);
                } catch (Exception parseEx) {
                    // 先提交此前已解析合法批次，保证部分恢复计数与文件行顺序一致
                    if (!batch.isEmpty()) {
                        restored += restoreBatch(instance, batch);
                        batch.clear();
                    }
                    throw parseEx;
                }
                batch.add(entry);
                if (batch.size() >= BackupSnapshotSupport.PIPELINE_BATCH_SIZE) {
                    restored += restoreBatch(instance, batch);
                    batch.clear();
                    long now = System.currentTimeMillis();
                    if ((restored - lastFlushedCount) >= BackupSnapshotSupport.PROGRESS_FLUSH_KEYS
                            || (now - lastProgressFlushAt) >= BackupSnapshotSupport.PROGRESS_FLUSH_MS) {
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
                    BackupSnapshotSupport.truncate("已回放 " + restored + " 个键，恢复中断：" + reason));
            auditService.record("RESTORE_BACKUP", instance.getName(),
                    "file=" + record.getFileName() + " restored=" + restored + " result=failed", false);
            log.error("恢复批次失败 recordId={} instance={} restored={}", recordId, instance.getName(), restored, failure);
            return restored;
        } catch (Exception ex) {
            // 异常安全：已写入的部分保留，状态明确标失败，并记录"恢复了多少"
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            finishRestore(record, RestoreStatus.FAILED, restored,
                    BackupSnapshotSupport.truncate("已回放 " + restored + " 个键，恢复中断：" + reason));
            auditService.record("RESTORE_BACKUP", instance.getName(),
                    "file=" + record.getFileName() + " restored=" + restored + " result=failed", false);
            log.error("恢复失败 recordId={} instance={} restored={}", recordId, instance.getName(), restored, ex);
            return restored;
        }
    }

    /** 写终态并落库；无论成功失败都必须执行，避免实例停留在 RESTORING。 */
    void finishRestore(BackupRecord record, RestoreStatus status, long restored, String message) {
        record.setRestoreStatus(status);
        record.setRestoreFinishedAt(LocalDateTime.now());
        record.setRestoredKeys(restored);
        record.setRestoreMessage(message);
        repository.save(record);
        if (metricsBinder != null && status != null) {
            metricsBinder.recordRestore(status.name());
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
    private long restoreBatch(DbInstance instance, List<BackupService.SnapshotEntry> entries) throws IOException {
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
    private void appendRestoreCommands(BackupService.SnapshotEntry entry,
                                       List<List<byte[]>> commands,
                                       List<String> ops) throws IOException {
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
}
