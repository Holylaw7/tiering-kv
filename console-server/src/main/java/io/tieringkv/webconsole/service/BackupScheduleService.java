package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupSchedule;
import io.tieringkv.webconsole.domain.BackupScheduleMode;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.repository.BackupScheduleRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.web.dto.BackupScheduleRequest;
import io.tieringkv.webconsole.web.dto.BackupScheduleRunResult;
import io.tieringkv.webconsole.web.dto.BackupScheduleView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 定时备份策略服务（v6.1.0 A2）：
 * <ul>
 *   <li>支持标准 5 段 Unix Cron 与 6 段 Spring Cron，支持逻辑导出（LOGICAL）与物理快照（CHECKPOINT）双模式；</li>
 *   <li>多副本控制面通过 {@link DbLeaseLockService}（{@code scheduler:backup-schedule}）单活调度，零重复触发；</li>
 *   <li>执行逻辑备份后按策略 {@code retentionCount} 复用安全认领清理旧备份。</li>
 * </ul>
 */
@Service
public class BackupScheduleService {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduleService.class);
    static final String BACKUP_SCHEDULE_LEASE_KEY = "scheduler:backup-schedule";
    private static final Duration SCHEDULE_LEASE_TTL = Duration.ofSeconds(25);

    private final BackupScheduleRepository repository;
    private final DbInstanceRepository instanceRepository;
    private final InstanceService instanceService;
    private final BackupService backupService;
    private final BackupRetentionService retentionService;
    private final AuditService auditService;
    private final ConsoleProperties properties;
    private final DbLeaseLockService leaseLockService;

    @Autowired
    public BackupScheduleService(BackupScheduleRepository repository,
                                 DbInstanceRepository instanceRepository,
                                 InstanceService instanceService,
                                 BackupService backupService,
                                 BackupRetentionService retentionService,
                                 AuditService auditService,
                                 ConsoleProperties properties,
                                 DbLeaseLockService leaseLockService) {
        this.repository = repository;
        this.instanceRepository = instanceRepository;
        this.instanceService = instanceService;
        this.backupService = backupService;
        this.retentionService = retentionService;
        this.auditService = auditService;
        this.properties = properties;
        this.leaseLockService = leaseLockService;
    }

    public List<BackupScheduleView> list(Long instanceId) {
        List<BackupSchedule> schedules = instanceId == null
                ? repository.findAllByOrderByIdDesc()
                : repository.findByInstanceIdOrderByIdDesc(instanceId);
        Map<Long, String> instanceNames = instanceRepository.findAll().stream()
                .collect(Collectors.toMap(DbInstance::getId, DbInstance::getName, (a, b) -> a));
        return schedules.stream()
                .map(s -> BackupScheduleView.of(s, instanceNames.getOrDefault(s.getInstanceId(), "#" + s.getInstanceId())))
                .toList();
    }

    @Transactional
    public BackupScheduleView create(BackupScheduleRequest request) {
        DbInstance instance = instanceService.require(request.instanceId());
        String name = request.name().trim();
        if (repository.existsByName(name)) {
            throw BusinessException.conflict("定时备份策略名称已存在：" + name);
        }
        String normalizedCron = normalizeCron(request.cronExpression());
        CronExpression cron = parseCron(normalizedCron);
        BackupScheduleMode mode = request.mode() == null ? BackupScheduleMode.LOGICAL : request.mode();
        int retention = request.retentionCount() == null ? 7 : request.retentionCount();
        boolean enabled = request.enabled() == null || request.enabled();

        BackupSchedule schedule = new BackupSchedule();
        schedule.setInstanceId(instance.getId());
        schedule.setName(name);
        schedule.setCronExpression(normalizedCron);
        schedule.setMode(mode);
        schedule.setRetentionCount(retention);
        schedule.setEnabled(enabled);
        schedule.setNextRunAt(enabled ? nextExecution(cron, LocalDateTime.now()) : null);
        BackupSchedule saved = repository.save(schedule);

        auditService.record("BACKUP_SCHEDULE_CREATE", name,
                "instance=" + instance.getName() + ",cron=" + normalizedCron
                        + ",mode=" + mode + ",retention=" + retention + ",enabled=" + enabled, true);
        return BackupScheduleView.of(saved, instance.getName());
    }

    @Transactional
    public BackupScheduleView update(Long id, BackupScheduleRequest request) {
        BackupSchedule schedule = require(id);
        DbInstance instance = instanceService.require(request.instanceId());
        String name = request.name().trim();
        if (repository.existsByNameAndIdNot(name, id)) {
            throw BusinessException.conflict("定时备份策略名称已存在：" + name);
        }
        String normalizedCron = normalizeCron(request.cronExpression());
        CronExpression cron = parseCron(normalizedCron);
        BackupScheduleMode mode = request.mode() == null ? BackupScheduleMode.LOGICAL : request.mode();
        int retention = request.retentionCount() == null ? 7 : request.retentionCount();
        boolean enabled = request.enabled() == null || request.enabled();

        schedule.setInstanceId(instance.getId());
        schedule.setName(name);
        schedule.setCronExpression(normalizedCron);
        schedule.setMode(mode);
        schedule.setRetentionCount(retention);
        schedule.setEnabled(enabled);
        schedule.setNextRunAt(enabled ? nextExecution(cron, LocalDateTime.now()) : null);
        BackupSchedule saved = repository.save(schedule);

        auditService.record("BACKUP_SCHEDULE_UPDATE", name,
                "instance=" + instance.getName() + ",cron=" + normalizedCron
                        + ",mode=" + mode + ",retention=" + retention + ",enabled=" + enabled, true);
        return BackupScheduleView.of(saved, instance.getName());
    }

    @Transactional
    public void delete(Long id) {
        BackupSchedule schedule = require(id);
        repository.delete(schedule);
        auditService.record("BACKUP_SCHEDULE_DELETE", schedule.getName(),
                "id=" + id + ",instanceId=" + schedule.getInstanceId(), true);
    }

    /** 立即手动执行一次策略（不影响其既定 Cron 排期）。 */
    public BackupScheduleRunResult runNow(Long id) {
        BackupSchedule schedule = require(id);
        return executeSchedule(schedule, false);
    }

    /** 后台轮询入口：多副本部署通过数据库租约锁选主，确保同一时刻仅一个控制面副本派发到期策略。 */
    @Scheduled(fixedDelayString = "${console.backup.schedule.poll-interval-ms:30000}")
    public void scheduledTick() {
        if (!properties.getBackup().getSchedule().isEnabled()) {
            return;
        }
        if (leaseLockService != null
                && !leaseLockService.tryAcquire(BACKUP_SCHEDULE_LEASE_KEY, SCHEDULE_LEASE_TTL)) {
            return;
        }
        try {
            runDueSchedules(LocalDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("定时备份策略调度轮询异常", ex);
        }
    }

    /** 扫描并执行所有已到期的启用策略（可由单测直接调用，避免等待时钟）。 */
    public int runDueSchedules(LocalDateTime now) {
        List<BackupSchedule> due = repository
                .findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now);
        int executed = 0;
        for (BackupSchedule schedule : due) {
            // 先推进下次触发时间并落库，防止下游故障时每 30 秒热循环重试
            try {
                CronExpression cron = parseCron(schedule.getCronExpression());
                schedule.setNextRunAt(nextExecution(cron, now));
            } catch (BusinessException invalid) {
                schedule.setEnabled(false);
                schedule.setNextRunAt(null);
                schedule.setLastStatus("FAILED");
                schedule.setLastMessage(truncate(invalid.getMessage()));
                schedule.setLastRunAt(now);
                repository.save(schedule);
                continue;
            }
            repository.save(schedule);
            executeSchedule(schedule, true);
            executed++;
        }
        return executed;
    }

    private BackupScheduleRunResult executeSchedule(BackupSchedule schedule, boolean scheduled) {
        LocalDateTime executedAt = LocalDateTime.now();
        String action = scheduled ? "BACKUP_SCHEDULE_TRIGGER" : "BACKUP_SCHEDULE_RUN";
        try {
            if (schedule.getMode() == BackupScheduleMode.CHECKPOINT) {
                BackupService.CheckpointResult result = backupService.triggerCheckpoint(schedule.getInstanceId());
                String status = result.triggered() ? "SUCCESS" : "SKIPPED";
                String message = result.message() == null || result.message().isBlank()
                        ? (result.triggered() ? "已触发物理 Checkpoint (BGSAVE)" : "物理快照正在执行中，已跳过")
                        : result.message();
                updateRunStatus(schedule, executedAt, status, message);
                auditService.record(action, schedule.getName(),
                        "mode=CHECKPOINT,status=" + status + "," + message, true);
                return new BackupScheduleRunResult(schedule.getId(), schedule.getMode(),
                        status, message, null, executedAt);
            }

            BackupRecord record = backupService.export(schedule.getInstanceId());
            int purged = 0;
            if (retentionService != null && schedule.getRetentionCount() >= 1) {
                try {
                    purged = retentionService.purgeInstance(schedule.getInstanceId(), schedule.getRetentionCount());
                } catch (RuntimeException purgeEx) {
                    log.warn("定时策略留存清理失败 scheduleId={}", schedule.getId(), purgeEx);
                }
            }
            String message = "已提交逻辑备份 #" + record.getId() + " (" + record.getFileName() + ")"
                    + (purged > 0 ? "，清理旧备份 " + purged + " 份" : "");
            updateRunStatus(schedule, executedAt, "SUCCESS", message);
            auditService.record(action, schedule.getName(),
                    "mode=LOGICAL,backupId=" + record.getId() + ",purged=" + purged, true);
            return new BackupScheduleRunResult(schedule.getId(), schedule.getMode(),
                    "SUCCESS", message, record.getId(), executedAt);
        } catch (BusinessException ex) {
            String msg = truncate(ex.getMessage());
            updateRunStatus(schedule, executedAt, "FAILED", msg);
            auditService.record(action, schedule.getName(), msg, false);
            if (!scheduled) {
                throw ex;
            }
            return new BackupScheduleRunResult(schedule.getId(), schedule.getMode(),
                    "FAILED", msg, null, executedAt);
        } catch (RuntimeException ex) {
            String msg = truncate(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            updateRunStatus(schedule, executedAt, "FAILED", msg);
            auditService.record(action, schedule.getName(), msg, false);
            log.warn("执行定时备份策略失败 scheduleId={} name={}", schedule.getId(), schedule.getName(), ex);
            if (!scheduled) {
                throw ex;
            }
            return new BackupScheduleRunResult(schedule.getId(), schedule.getMode(),
                    "FAILED", msg, null, executedAt);
        }
    }

    private void updateRunStatus(BackupSchedule schedule, LocalDateTime executedAt,
                                 String status, String message) {
        schedule.setLastRunAt(executedAt);
        schedule.setLastStatus(status);
        schedule.setLastMessage(truncate(message));
        repository.save(schedule);
    }

    private BackupSchedule require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("定时备份策略不存在：" + id));
    }

    /** 规范化 Cron：去除多余空白，兼容 5 段 Unix Cron（自动补秒字段 0）与 6 段 Spring Cron。 */
    static String normalizeCron(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.badRequest("Cron 表达式不能为空");
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        String[] fields = collapsed.split(" ");
        if (fields.length == 5) {
            return "0 " + collapsed;
        }
        if (fields.length == 6) {
            return collapsed;
        }
        throw BusinessException.badRequest("Cron 表达式必须为 5 段（Unix）或 6 段（Spring）格式：" + raw);
    }

    static CronExpression parseCron(String normalized) {
        try {
            return CronExpression.parse(normalizeCron(normalized));
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("无效的 Cron 表达式：" + normalized + " (" + ex.getMessage() + ")");
        }
    }

    private static LocalDateTime nextExecution(CronExpression cron, LocalDateTime from) {
        LocalDateTime next = cron.next(from);
        if (next == null) {
            throw BusinessException.badRequest("Cron 表达式不会在未来触发");
        }
        return next;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
