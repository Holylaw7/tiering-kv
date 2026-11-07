package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 备份保留策略与安全删除（ADR-0367）。
 *
 * <p>三条不变量：
 * <ol>
 *   <li>只清理"已完成且允许清理"的备份：RUNNING / RESTORING 一律不动，最近一个 SUCCESS 永远保留；</li>
 *   <li>删除权通过数据库条件更新认领，多实例并发清理只有一个能成功；</li>
 *   <li>先认领、再删文件、最后删记录，任一步失败都能在下轮收敛。</li>
 * </ol>
 */
@Service
public class BackupRetentionService {

    private static final Logger log = LoggerFactory.getLogger(BackupRetentionService.class);
    private static final String RETIRING = "RETIRING";

    private final BackupRecordRepository repository;
    private final DbInstanceRepository instanceRepository;
    private final ConsoleProperties properties;
    private final Path directory;
    private final Object lock = new Object();
    private volatile PurgeReport lastReport;

    public BackupRetentionService(BackupRecordRepository repository, DbInstanceRepository instanceRepository,
                                  ConsoleProperties properties) {
        this.repository = repository;
        this.instanceRepository = instanceRepository;
        this.properties = properties;
        this.directory = Paths.get(properties.getBackup().getDir()).toAbsolutePath().normalize();
    }

    /** 一次清理的结果，用于接口与审计。 */
    public record PurgeReport(LocalDateTime startedAt, LocalDateTime finishedAt, int instances,
                              int candidates, int deleted, int failed, int skipped, int orphanRemoved) {
    }

    /** 保留策略的只读视图，供 GET /backups/retention 返回。 */
    public record RetentionView(int defaultCount, int failedDays, int claimTimeoutMinutes,
                                int orphanGraceMinutes, long intervalMs, boolean enabled,
                                PurgeReport lastReport) {
    }

    public RetentionView view() {
        ConsoleProperties.Retention r = properties.getBackup().getRetention();
        return new RetentionView(r.getDefaultCount(), r.getFailedDays(), r.getClaimTimeoutMinutes(),
                r.getOrphanGraceMinutes(), r.getIntervalMs(), r.isEnabled(), lastReport);
    }

    /** 定时清理：默认每小时一次；单进程内串行，跨进程由数据库认领协调。 */
    @Scheduled(fixedDelayString = "${console.backup.retention.interval-ms:3600000}")
    public void scheduledPurge() {
        if (!properties.getBackup().getRetention().isEnabled()) {
            return;
        }
        try {
            purge();
        } catch (RuntimeException ex) {
            log.warn("定时备份清理失败", ex);
        }
    }

    /** 执行一次清理；同一进程内串行执行，避免定时任务与手工触发重叠。 */
    @Transactional
    public PurgeReport purge() {
        synchronized (lock) {
            LocalDateTime started = LocalDateTime.now();
            int instances = 0;
            int candidates = 0;
            int deleted = 0;
            int failed = 0;
            int skipped = 0;

            LocalDateTime staleBefore = started.minusMinutes(
                    properties.getBackup().getRetention().getClaimTimeoutMinutes());
            repository.releaseStaleClaims(staleBefore);

            for (DbInstance instance : instanceRepository.findAll()) {
                instances++;
                List<BackupRecord> records = repository.findByInstanceId(instance.getId());
                int keep = instance.getBackupRetention() != null
                        ? instance.getBackupRetention()
                        : properties.getBackup().getRetention().getDefaultCount();
                List<BackupRecord> successes = records.stream()
                        .filter(r -> r.getStatus() == BackupStatus.SUCCESS)
                        .sorted(Comparator.comparing(BackupRecord::getCreatedAt).reversed())
                        .toList();
                // 下标 0 永远保留（最近一个可恢复点），其后按 keep 截断
                List<BackupRecord> removable = successes.stream().skip(Math.max(keep, 1)).toList();
                for (BackupRecord record : removable) {
                    candidates++;
                    Outcome outcome = retire(record, staleBefore);
                    if (outcome == Outcome.DELETED) {
                        deleted++;
                    } else if (outcome == Outcome.SKIPPED) {
                        skipped++;
                    } else {
                        failed++;
                    }
                }
            }

            // 失败记录按时间清理（不占 SUCCESS 名额）
            LocalDateTime failedBefore = started.minusDays(
                    properties.getBackup().getRetention().getFailedDays());
            for (BackupRecord record : repository.findByStatusAndCreatedAtBefore(BackupStatus.FAILED, failedBefore)) {
                candidates++;
                Outcome outcome = retire(record, staleBefore);
                if (outcome == Outcome.DELETED) {
                    deleted++;
                } else if (outcome == Outcome.SKIPPED) {
                    skipped++;
                } else {
                    failed++;
                }
            }

            // 认领后崩溃/删除失败的记录：只有认领超时或文件已消失时才接手，避免与在跑的实例抢删
            for (BackupRecord record : repository.findByDeleteState(RETIRING)) {
                boolean stale = record.getDeleteClaimedAt() == null
                        || record.getDeleteClaimedAt().isBefore(staleBefore);
                boolean fileGone = !Files.exists(directory.resolve(record.getFileName()).normalize());
                if (!stale && !fileGone) {
                    skipped++;
                    continue;
                }
                Outcome outcome = finishRetire(record);
                if (outcome == Outcome.DELETED) {
                    deleted++;
                } else if (outcome == Outcome.FAILED) {
                    failed++;
                }
            }

            int orphanRemoved = removeOrphanFiles(started);
            PurgeReport report = new PurgeReport(started, LocalDateTime.now(), instances,
                    candidates, deleted, failed, skipped, orphanRemoved);
            lastReport = report;
            if (deleted > 0 || failed > 0 || orphanRemoved > 0) {
                log.info("备份清理完成：删除 {} 个，失败 {} 个，跳过 {} 个，孤儿文件 {} 个",
                        deleted, failed, skipped, orphanRemoved);
            }
            return report;
        }
    }

    private enum Outcome { DELETED, SKIPPED, FAILED }

    /** 认领 → 删文件 → 删记录；认领失败说明别人在处理，跳过。 */
    private Outcome retire(BackupRecord record, LocalDateTime staleBefore) {
        int claimed = repository.claimDeletion(record.getId(), LocalDateTime.now());
        if (claimed == 0) {
            // 可能是别人刚认领，也可能是状态已变化（RUNNING/RESTORING）
            return Outcome.SKIPPED;
        }
        return finishRetire(record);
    }

    private Outcome finishRetire(BackupRecord record) {
        Path file = directory.resolve(record.getFileName()).normalize();
        try {
            if (file.startsWith(directory)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ex) {
            log.warn("删除备份文件失败，保留记录等待重试：{}", record.getFileName(), ex);
            return Outcome.FAILED;
        }
        try {
            // 幂等：另一个实例可能已经删掉了这条记录
            if (repository.existsById(record.getId())) {
                repository.delete(record);
            }
            return Outcome.DELETED;
        } catch (RuntimeException ex) {
            log.warn("删除备份记录失败，下轮重试：{}", record.getId(), ex);
            return Outcome.FAILED;
        }
    }

    /** 回收没有记录引用的备份文件；只删超过 grace 的，避免误删正在写入的备份。 */
    private int removeOrphanFiles(LocalDateTime now) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        long graceMillis = properties.getBackup().getRetention().getOrphanGraceMinutes() * 60_000L;
        int removed = 0;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String name = file.getFileName().toString();
                if (repository.existsByFileName(name)) {
                    continue;
                }
                try {
                    long age = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            - Files.getLastModifiedTime(file).toMillis();
                    if (age > graceMillis) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException ex) {
                    log.warn("删除孤儿备份文件失败：{}", name, ex);
                }
            }
        } catch (IOException ex) {
            log.warn("扫描备份目录失败：{}", directory, ex);
        }
        return removed;
    }

    /** 手工删除单个备份：走同一套保护与认领协议。 */
    @Transactional
    public void delete(Long recordId) {
        BackupRecord record = repository.findById(recordId)
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在：" + recordId));
        if (record.getStatus() == BackupStatus.RUNNING) {
            throw BusinessException.conflict("备份正在进行，不能删除");
        }
        if (record.getRestoreStatus() == io.tieringkv.webconsole.domain.RestoreStatus.RESTORING) {
            throw BusinessException.conflict("该备份正在被恢复，不能删除");
        }
        int claimed = repository.claimDeletion(recordId, LocalDateTime.now());
        if (claimed == 0) {
            // 失败记录也允许删除：直接走删除收尾（无认领语义）
            if (record.getStatus() == BackupStatus.FAILED) {
                finishRetire(record);
                return;
            }
            throw BusinessException.conflict("该备份正在被删除或状态已变化，请稍后重试");
        }
        finishRetire(record);
    }
}