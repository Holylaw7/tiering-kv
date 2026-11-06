package io.tieringkv.webconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.support.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 逻辑备份：SCAN 全量导出为 JSONL，可按类型回放恢复。 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final int SCAN_COUNT = 200;
    private static final int MAX_KEYS = 200_000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BackupRecordRepository repository;
    private final InstanceService instanceService;
    private final KeyspaceService keyspaceService;
    private final RespGateway gateway;
    private final ObjectMapper objectMapper;
    private final Path directory;
    private final Executor executor;

    public BackupService(BackupRecordRepository repository, InstanceService instanceService,
                         KeyspaceService keyspaceService, RespGateway gateway,
                         ObjectMapper objectMapper, ConsoleProperties properties,
                         @Qualifier("backupExecutor") Executor executor) {
        this.repository = repository;
        this.instanceService = instanceService;
        this.keyspaceService = keyspaceService;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.directory = Paths.get(properties.getBackup().getDir()).toAbsolutePath().normalize();
        this.executor = executor;
    }

    /** 备份条目（JSONL 一行）。 */
    public record SnapshotEntry(String key, String type, long ttl, Object value) {
    }

    public Path directory() {
        return directory;
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
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> exportLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public BackupRecord export(Long instanceId) {
        instanceService.require(instanceId);
        Object lock = exportLocks.computeIfAbsent(instanceId, key -> new Object());
        BackupRecord created;
        synchronized (lock) {
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
            record.setFileName("backup-" + instanceId + "-" + LocalDateTime.now().format(STAMP) + ".jsonl");
            record.setStatus(BackupStatus.RUNNING);
            record.setCreatedAt(LocalDateTime.now());
            created = repository.save(record);
        }

        Long recordId = created.getId();
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
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(record.getFileName());
            long keys = writeSnapshot(instance, file);
            record.setKeyCount(keys);
            record.setSizeBytes(Files.size(file));
            record.setStatus(BackupStatus.SUCCESS);
            record.setMessage("导出 " + keys + " 个键");
        } catch (IOException | RuntimeException ex) {
            record.setStatus(BackupStatus.FAILED);
            record.setMessage(truncate(ex.getMessage()));
            log.error("备份失败 instance={}", instance.getName(), ex);
        }
        record.setFinishedAt(LocalDateTime.now());
        repository.save(record);
    }

    private long writeSnapshot(DbInstance instance, Path file) throws IOException {
        long count = 0;
        String cursor = "0";
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            do {
                RespReply reply = gateway.execute(instance, "SCAN", cursor, "MATCH", "*",
                        "COUNT", String.valueOf(SCAN_COUNT));
                if (reply instanceof RespReply.Error error) {
                    throw new IOException("SCAN 失败：" + error.message());
                }
                if (!(reply instanceof RespReply.Array array) || array.items().size() < 2) {
                    throw new IOException("SCAN 返回结构异常");
                }
                cursor = text(array.items().get(0));
                if (array.items().get(1) instanceof RespReply.Array keyArray && !keyArray.isNil()) {
                    for (RespReply item : keyArray.items()) {
                        String key = Text.decode(
                                item instanceof RespReply.Bulk bulk ? bulk.value() : null);
                        if (key == null) {
                            continue;
                        }
                        String type = keyspaceService.type(instance, key);
                        long ttl = keyspaceService.ttl(instance, key);
                        Object value = keyspaceService.value(instance, key, type);
                        writer.write(objectMapper.writeValueAsString(
                                new SnapshotEntry(key, type, ttl, value)));
                        writer.newLine();
                        count++;
                        if (count >= MAX_KEYS) {
                            log.warn("备份键数达到上限 {}，提前结束", MAX_KEYS);
                            return count;
                        }
                    }
                }
            } while (!"0".equals(cursor));
        }
        return count;
    }

    /** 从备份文件回放（先删同键再写入，保持幂等）。 */
    public long restore(Long recordId) {
        BackupRecord record = repository.findById(recordId)
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在：" + recordId));
        if (record.getStatus() != BackupStatus.SUCCESS) {
            throw BusinessException.badRequest("仅成功的备份可恢复");
        }
        DbInstance instance = instanceService.require(record.getInstanceId());
        Path file = directory.resolve(record.getFileName());
        if (!Files.exists(file)) {
            throw BusinessException.notFound("备份文件已丢失：" + record.getFileName());
        }
        long restored = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                SnapshotEntry entry = objectMapper.readValue(line, SnapshotEntry.class);
                restoreEntry(instance, entry);
                restored++;
            }
        } catch (IOException ex) {
            throw BusinessException.upstream("恢复失败：" + ex.getMessage());
        }
        return restored;
    }

    @SuppressWarnings("unchecked")
    private void restoreEntry(DbInstance instance, SnapshotEntry entry) throws IOException {
        String key = entry.key();
        if (key == null || entry.value() == null) {
            return;
        }
        gateway.execute(instance, "DEL", key);
        switch (entry.type()) {
            case "string" -> gateway.execute(instance, "SET", key, String.valueOf(entry.value()));
            case "hash" -> {
                Map<String, String> hash = (Map<String, String>) entry.value();
                List<String> args = new ArrayList<>();
                args.add("HSET");
                args.add(key);
                hash.forEach((field, value) -> {
                    args.add(field);
                    args.add(value);
                });
                if (args.size() > 2) {
                    gateway.execute(instance, args.stream()
                            .map(item -> item.getBytes(StandardCharsets.UTF_8)).toList());
                }
            }
            case "list" -> {
                List<String> list = (List<String>) entry.value();
                for (String item : list) {
                    gateway.execute(instance, "RPUSH", key, item);
                }
            }
            case "set" -> {
                List<String> set = (List<String>) entry.value();
                for (String item : set) {
                    gateway.execute(instance, "SADD", key, item);
                }
            }
            case "zset" -> {
                List<Map<String, String>> scored = (List<Map<String, String>>) entry.value();
                for (Map<String, String> pair : scored) {
                    gateway.execute(instance, "ZADD", key,
                            pair.getOrDefault("score", "0"), pair.getOrDefault("member", ""));
                }
            }
            case "json" -> gateway.execute(instance, "JSON.SET", key, "$",
                    String.valueOf(entry.value()));
            default -> log.debug("跳过不支持的类型恢复：{} ({})", key, entry.type());
        }
        if (entry.ttl() > 0) {
            gateway.execute(instance, "EXPIRE", key, String.valueOf(entry.ttl()));
        }
    }

    public Path fileOf(Long recordId) {
        BackupRecord record = repository.findById(recordId)
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在：" + recordId));
        Path file = directory.resolve(record.getFileName()).normalize();
        if (!file.startsWith(directory)) {
            throw BusinessException.badRequest("非法备份路径");
        }
        if (!Files.exists(file)) {
            throw BusinessException.notFound("备份文件不存在：" + record.getFileName());
        }
        return file;
    }

    public void delete(Long recordId) {
        BackupRecord record = repository.findById(recordId)
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在：" + recordId));
        try {
            Files.deleteIfExists(directory.resolve(record.getFileName()));
        } catch (IOException ex) {
            log.warn("删除备份文件失败 {}", record.getFileName(), ex);
        }
        repository.delete(record);
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