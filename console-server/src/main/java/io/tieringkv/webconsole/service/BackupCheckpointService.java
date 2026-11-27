package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.support.InfoParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * 物理快照（BGSAVE / LASTSAVE / INFO persistence）协作服务（Wave 3B M2）。
 */
@Service
public class BackupCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(BackupCheckpointService.class);

    private final InstanceService instanceService;
    private final RespGateway gateway;
    private final AuditService auditService;

    public BackupCheckpointService(InstanceService instanceService,
                                   RespGateway gateway,
                                   AuditService auditService) {
        this.instanceService = instanceService;
        this.gateway = gateway;
        this.auditService = auditService;
    }

    /** 触发数据面异步物理快照 BGSAVE（WAL + Cold SSTable + 向量索引），结果记入审计。 */
    public BackupService.CheckpointResult triggerCheckpoint(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        try {
            RespReply reply = gateway.execute(instance, "BGSAVE");
            if (reply instanceof RespReply.Simple simple) {
                long lastSave = queryLastSaveQuietly(instance);
                auditService.record("BACKUP_CHECKPOINT", instance.getName(), simple.value(), true);
                return new BackupService.CheckpointResult(true, simple.value(), lastSave);
            }
            if (reply instanceof RespReply.Error error) {
                String msg = error.message() == null ? "" : error.message();
                String lower = msg.toLowerCase(Locale.ROOT);
                auditService.record("BACKUP_CHECKPOINT", instance.getName(), BackupSnapshotSupport.truncate(msg), false);
                if (lower.contains("already in progress")) {
                    return new BackupService.CheckpointResult(false, msg, queryLastSaveQuietly(instance));
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
            auditService.record("BACKUP_CHECKPOINT", instance.getName(),
                    BackupSnapshotSupport.truncate(ex.getMessage()), false);
            throw BusinessException.upstream("触发 BGSAVE 失败：" + ex.getMessage());
        }
    }

    /** 查询实例最近一次物理快照状态（LASTSAVE + INFO persistence），兼容旧版数据面降级。 */
    public BackupService.CheckpointStatusView checkpointStatus(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        long lastSave = 0L;
        try {
            RespReply lastReply = gateway.execute(instance, "LASTSAVE");
            if (lastReply instanceof RespReply.Integer integer) {
                lastSave = integer.value();
            } else if (lastReply instanceof RespReply.Error err) {
                String msg = err.message() == null ? "" : err.message();
                if (msg.toLowerCase(Locale.ROOT).contains("unknown command")) {
                    return new BackupService.CheckpointStatusView(false, false, 0L, "unsupported", -1L, 0L, null);
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

        return new BackupService.CheckpointStatusView(true, inProgress, lastSave, lastStatus,
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
}
