package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.repository.LoginAttemptRepository;
import io.tieringkv.webconsole.repository.MetricPointRepository;
import io.tieringkv.webconsole.repository.OpLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/** 数据保留策略：定时清理过期指标、审计日志与登录限流窗口，防止库无界增长。 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    static final String RETENTION_LEASE_KEY = "scheduler:data-retention";

    private final MetricPointRepository metricRepository;
    private final OpLogRepository opLogRepository;
    private final ConsoleProperties properties;
    private final LoginAttemptRepository loginAttemptRepository;
    private final DbLeaseLockService leaseLockService;
    private AlertStateStore alertStateStore;

    public RetentionService(MetricPointRepository metricRepository,
                            OpLogRepository opLogRepository,
                            ConsoleProperties properties) {
        this(metricRepository, opLogRepository, properties, null, null);
    }

    @Autowired
    public RetentionService(MetricPointRepository metricRepository,
                            OpLogRepository opLogRepository,
                            ConsoleProperties properties,
                            LoginAttemptRepository loginAttemptRepository,
                            DbLeaseLockService leaseLockService) {
        this.metricRepository = metricRepository;
        this.opLogRepository = opLogRepository;
        this.properties = properties;
        this.loginAttemptRepository = loginAttemptRepository;
        this.leaseLockService = leaseLockService;
    }

    @Autowired(required = false)
    void setAlertStateStore(AlertStateStore alertStateStore) {
        this.alertStateStore = alertStateStore;
    }

    /** 每小时清理一次：多副本由租约锁选主执行；指标按配置保留，审计保留 30 天，清理过期登录限流桶与告警状态。 */
    @Scheduled(fixedDelayString = "${console.retention.interval-ms:3600000}",
            initialDelayString = "${console.retention.initial-delay-ms:300000}")
    @Transactional
    public void purge() {
        if (leaseLockService != null
                && !leaseLockService.tryAcquire(RETENTION_LEASE_KEY, Duration.ofMinutes(5))) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int hours = Math.max(1, properties.getMetrics().getRetentionHours());
        int removedMetrics = metricRepository.deleteOlderThan(now.minusHours(hours));
        int removedLogs = opLogRepository.deleteOlderThan(now.minusDays(30));
        int removedAttempts = loginAttemptRepository == null ? 0 : loginAttemptRepository.deleteExpired(now);
        int removedAlertStates = alertStateStore == null ? 0 : alertStateStore.purgeStaleBefore(now.minusDays(7));
        if (removedMetrics > 0 || removedLogs > 0 || removedAttempts > 0 || removedAlertStates > 0) {
            log.info("保留策略清理完成：指标 {} 行、审计 {} 行、限流窗口 {} 行、过期告警态 {} 行",
                    removedMetrics, removedLogs, removedAttempts, removedAlertStates);
        }
    }
}
