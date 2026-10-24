package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.repository.MetricPointRepository;
import io.tieringkv.webconsole.repository.OpLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 数据保留策略：定时清理过期指标与审计日志，防止库无界增长。 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final MetricPointRepository metricRepository;
    private final OpLogRepository opLogRepository;
    private final ConsoleProperties properties;

    public RetentionService(MetricPointRepository metricRepository,
                            OpLogRepository opLogRepository,
                            ConsoleProperties properties) {
        this.metricRepository = metricRepository;
        this.opLogRepository = opLogRepository;
        this.properties = properties;
    }

    /** 每小时清理一次：指标按配置小时数保留，审计日志保留 30 天。 */
    @Scheduled(fixedDelayString = "${console.retention.interval-ms:3600000}",
            initialDelayString = "${console.retention.initial-delay-ms:300000}")
    @Transactional
    public void purge() {
        int hours = Math.max(1, properties.getMetrics().getRetentionHours());
        int removedMetrics = metricRepository.deleteOlderThan(LocalDateTime.now().minusHours(hours));
        int removedLogs = opLogRepository.deleteOlderThan(LocalDateTime.now().minusDays(30));
        if (removedMetrics > 0 || removedLogs > 0) {
            log.info("保留策略清理完成：指标 {} 行、审计 {} 行", removedMetrics, removedLogs);
        }
    }
}
