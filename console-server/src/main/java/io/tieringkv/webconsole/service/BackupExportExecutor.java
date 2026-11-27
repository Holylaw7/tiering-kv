package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.BackupVerifyStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.support.Text;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * 逻辑备份流式导出执行器（Wave 3B M2）：负责 SCAN 批量导出、GZIP/JSONL 编码、
 * 流式 SHA-256 摘要计算、进度节流落库与失败清理。
 */
@Service
public class BackupExportExecutor {

    private static final Logger log = LoggerFactory.getLogger(BackupExportExecutor.class);

    private final BackupRecordRepository repository;
    private final InstanceService instanceService;
    private final KeyspaceService keyspaceService;
    private final RespGateway gateway;
    private final ObjectMapper objectMapper;
    private final ConsoleProperties properties;
    private final BackupStorageBackend storageBackend;
    private final BackupCapacityService capacityService;
    private ConsoleMetricsBinder metricsBinder;

    public BackupExportExecutor(BackupRecordRepository repository,
                                InstanceService instanceService,
                                KeyspaceService keyspaceService,
                                RespGateway gateway,
                                ObjectMapper objectMapper,
                                ConsoleProperties properties,
                                BackupStorageBackend storageBackend,
                                BackupCapacityService capacityService) {
        this.repository = repository;
        this.instanceService = instanceService;
        this.keyspaceService = keyspaceService;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.storageBackend = storageBackend;
        this.capacityService = capacityService;
    }

    @Autowired(required = false)
    void setMetricsBinder(ConsoleMetricsBinder metricsBinder) {
        this.metricsBinder = metricsBinder;
    }

    private record WriteSnapshotResult(long keyCount, String sha256) {
    }

    /** 执行单条逻辑备份导出任务并落库终态。 */
    public void runExport(Long recordId) {
        BackupRecord record = repository.findById(recordId).orElse(null);
        if (record == null) {
            return;
        }
        DbInstance instance = instanceService.require(record.getInstanceId());
        String fileName = record.getFileName();
        long written = 0;
        try {
            WriteSnapshotResult snapshotResult = writeSnapshot(instance, record, fileName);
            long keys = snapshotResult.keyCount();
            written = storageBackend.publish(fileName);
            record.setKeyCount(keys);
            record.setProcessedKeys(keys);
            record.setSizeBytes(written);
            record.setSha256(snapshotResult.sha256());
            record.setVerifyStatus(BackupVerifyStatus.VERIFIED);
            record.setVerifiedAt(LocalDateTime.now());
            record.setStatus(BackupStatus.SUCCESS);
            record.setMessage("导出 " + keys + " 个键");
        } catch (IOException | RuntimeException ex) {
            // 磁盘写满、只读、进程级异常：临时文件必须清掉，记录必须失败
            storageBackend.deleteTempQuietly(fileName);
            record.setStatus(BackupStatus.FAILED);
            record.setMessage(BackupSnapshotSupport.truncate(ex.getMessage()));
            log.error("备份失败 instance={}", instance.getName(), ex);
        } finally {
            capacityService.release(recordId, written);
        }
        record.setFinishedAt(LocalDateTime.now());
        repository.save(record);
        if (metricsBinder != null && record.getStatus() != null) {
            metricsBinder.recordBackup(record.getStatus().name());
        }
    }

    private WriteSnapshotResult writeSnapshot(DbInstance instance, BackupRecord record, String fileName)
            throws IOException {
        long count = 0;
        long lastProgressFlushAt = System.currentTimeMillis();
        String cursor = "0";
        int scanCount = Math.max(50, properties.getBackup().getScanCount());
        long maxKeys = properties.getBackup().getMaxKeys();
        boolean compress = fileName.endsWith(".gz");
        MessageDigest digest = BackupSnapshotSupport.sha256Digest();
        try (OutputStream rawOut = storageBackend.openTempOutput(fileName);
             DigestOutputStream digestOut = new DigestOutputStream(rawOut, digest);
             OutputStream dataOut = compress ? new GZIPOutputStream(digestOut, 8192) : digestOut;
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
                cursor = BackupSnapshotSupport.text(array.items().get(0));
                if (array.items().get(1) instanceof RespReply.Array keyArray && !keyArray.isNil()) {
                    List<String> pageKeys = new ArrayList<>(keyArray.items().size());
                    for (RespReply item : keyArray.items()) {
                        String key = Text.decode(
                                item instanceof RespReply.Bulk bulk ? bulk.value() : null);
                        if (key != null) {
                            pageKeys.add(key);
                        }
                    }
                    for (int offset = 0; offset < pageKeys.size();
                         offset += BackupSnapshotSupport.PIPELINE_BATCH_SIZE) {
                        int end = Math.min(offset + BackupSnapshotSupport.PIPELINE_BATCH_SIZE, pageKeys.size());
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
                                    new BackupService.SnapshotEntry(meta.key(), meta.type(), meta.ttl(), val)));
                            writer.newLine();
                            count++;
                            if (maxKeys > 0 && count >= maxKeys) {
                                throw new IllegalStateException(
                                        "备份键数超过配置上限 " + maxKeys + "，已中止以避免生成不完整备份");
                            }
                        }
                        long now = System.currentTimeMillis();
                        if (count > 0 && (count % BackupSnapshotSupport.PROGRESS_FLUSH_KEYS
                                < BackupSnapshotSupport.PIPELINE_BATCH_SIZE
                                || (now - lastProgressFlushAt) >= BackupSnapshotSupport.PROGRESS_FLUSH_MS)) {
                            record.setProcessedKeys(count);
                            repository.save(record);
                            lastProgressFlushAt = now;
                        }
                    }
                }
            } while (!"0".equals(cursor));
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        return new WriteSnapshotResult(count, sha256);
    }
}
