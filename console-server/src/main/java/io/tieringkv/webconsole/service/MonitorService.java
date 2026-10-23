package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.InstanceStatus;
import io.tieringkv.webconsole.domain.MetricPoint;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.MetricPointRepository;
import io.tieringkv.webconsole.support.InfoParser;
import io.tieringkv.webconsole.web.dto.LiveMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 周期采集：INFO → 指标落库 → 告警求值。 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final InstanceService instanceService;
    private final RespGateway gateway;
    private final MetricPointRepository metricRepository;
    private final DbInstanceRepository instanceRepository;
    private final AlertService alertService;

    public MonitorService(InstanceService instanceService, RespGateway gateway,
                          MetricPointRepository metricRepository,
                          DbInstanceRepository instanceRepository,
                          AlertService alertService) {
        this.instanceService = instanceService;
        this.gateway = gateway;
        this.metricRepository = metricRepository;
        this.instanceRepository = instanceRepository;
        this.alertService = alertService;
    }

    /** 定时采集：fixedDelay 由配置给出，采集耗时不影响间隔。 */
    @Scheduled(fixedDelayString = "${console.metrics.interval-ms:5000}",
            initialDelayString = "${console.metrics.initial-delay-ms:3000}")
    public void scheduledCollect() {
        for (DbInstance instance : instanceService.enabled()) {
            try {
                collectOnce(instance);
            } catch (RuntimeException ex) {
                log.debug("采集失败 instance={} err={}", instance.getName(), ex.getMessage());
            }
        }
    }

    /** 采集单实例：写指标 + 更新状态 + 求值告警。 */
    public MetricPoint collectOnce(DbInstance instance) {
        LocalDateTime now = LocalDateTime.now();
        String infoText;
        try {
            infoText = gateway.info(instance);
        } catch (RuntimeException ex) {
            markStatus(instance, InstanceStatus.DOWN);
            return null;
        }
        Map<String, Map<String, String>> sections = InfoParser.parse(infoText);
        MetricPoint point = MetricMapper.toPoint(instance.getId(), sections, now);
        metricRepository.save(point);
        markStatus(instance, InstanceStatus.UP,
                InfoParser.get(sections, "Server", "redis_version"));
        alertService.evaluate(instance.getId(), MetricMapper.metricDictionary(point), now);
        return point;
    }

    /** 现场读取一次实时指标（不落库，供仪表盘首屏使用）。 */
    public Optional<LiveMetrics> live(DbInstance instance) {
        try {
            String infoText = gateway.info(instance);
            Map<String, Map<String, String>> sections = InfoParser.parse(infoText);
            Map<String, String> flat = InfoParser.flatten(sections);
            long usedMemory = parseLong(flat.get("used_memory"));
            long maxMemory = parseLong(flat.get("maxmemory"));
            return Optional.of(new LiveMetrics(
                    flat.get("redis_version"),
                    parseLong(flat.get("uptime_in_seconds")),
                    usedMemory, maxMemory,
                    parseLong(flat.get("used_memory_jvm")),
                    InfoParser.keyCount(sections),
                    (int) parseLong(flat.get("connected_clients")),
                    (int) parseLong(flat.get("active_requests")),
                    parseDouble(flat.get("qps")),
                    parseDouble(flat.get("instantaneous_ops_per_sec")),
                    parseDouble(flat.get("avg_latency_ms")),
                    parseLong(flat.get("errors")),
                    parseLong(flat.get("max_jvm_heap"))));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private void markStatus(DbInstance instance, InstanceStatus status) {
        markStatus(instance, status, null);
    }

    private void markStatus(DbInstance instance, InstanceStatus status, String version) {
        if (instance.getStatus() == status
                && (version == null || version.equals(instance.getVersion()))) {
            instance.setLastProbeAt(LocalDateTime.now());
            instanceRepository.save(instance);
            return;
        }
        instance.setStatus(status);
        if (version != null) {
            instance.setVersion(version);
        }
        instance.setLastProbeAt(LocalDateTime.now());
        instanceRepository.save(instance);
    }

    private static long parseLong(String raw) {
        try {
            return raw == null ? 0 : Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static double parseDouble(String raw) {
        try {
            return raw == null ? 0 : Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 仪表盘聚合用：按实例返回最新一条采样。 */
    public Map<Long, MetricPoint> latestByInstance() {
        Map<Long, MetricPoint> result = new LinkedHashMap<>();
        for (DbInstance instance : instanceService.enabled()) {
            metricRepository.findFirstByInstanceIdOrderByCollectedAtDesc(instance.getId())
                    .ifPresent(point -> result.put(instance.getId(), point));
        }
        return result;
    }
}
