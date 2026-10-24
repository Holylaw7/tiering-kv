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
import org.springframework.stereotype.Service;

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

    public BackupService(BackupRecordRepository repository, InstanceService instanceService,
                         KeyspaceService keyspaceService, RespGateway gateway,
                         ObjectMapper objectMapper, ConsoleProperties properties) {
        this.repository = repository;
        this.instanceService = instanceService;
        this.keyspaceService = keyspaceService;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.directory = Paths.get(properties.getBackup().getDir()).toAbsolutePath().normalize();
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

    /** 执行一次逻辑导出；异常时记录 FAILED 并抛出业务异常。 */
    public BackupRecord export(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        BackupRecord record = new BackupRecord();
        record.setInstanceId(instanceId);
        record.setFileName("backup-" + instanceId + "-" + LocalDateTime.now().format(STAMP) + ".jsonl");
        record.setStatus(BackupStatus.RUNNING);
        record.setCreatedAt(LocalDateTime.now());
        repository.save(record);
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
        if (record.getStatus() == BackupStatus.FAILED) {
            throw BusinessException.upstream("备份失败：" + record.getMessage());
        }
        return record;
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
