package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.InstanceStatus;
import io.tieringkv.webconsole.domain.MetricPoint;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.MetricPointRepository;
import io.tieringkv.webconsole.support.InfoParser;
import io.tieringkv.webconsole.web.dto.LiveMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 周期采集：INFO → 指标落库 → 告警求值。 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);
    static final String MONITOR_LEASE_KEY = "scheduler:monitor";

    private final InstanceService instanceService;
    private final RespGateway gateway;
    private final MetricPointRepository metricRepository;
    private final DbInstanceRepository instanceRepository;
    private final AlertService alertService;
    private final DbLeaseLockService leaseLockService;
    private final ConsoleProperties properties;
    private ConsoleMetricsBinder metricsBinder;
    private Executor collectExecutor;
    private static final long STATUS_PERSIST_INTERVAL_MS = 60_000L;
    private final Map<Long, Long> lastPersistedAt = new ConcurrentHashMap<>();
    /** 单实例在途采集去重集合：确保同一个慢实例最多占用 1 个工作线程，杜绝跨轮次堆积（Wave 2A H3）。 */
    private final Set<Long> inFlightInstances = ConcurrentHashMap.newKeySet();

    public MonitorService(InstanceService instanceService, RespGateway gateway,
                          MetricPointRepository metricRepository,
                          DbInstanceRepository instanceRepository,
                          AlertService alertService) {
        this(instanceService, gateway, metricRepository, instanceRepository, alertService, null, null);
    }

    @Autowired
    public MonitorService(InstanceService instanceService, RespGateway gateway,
                          MetricPointRepository metricRepository,
                          DbInstanceRepository instanceRepository,
                          AlertService alertService,
                          DbLeaseLockService leaseLockService,
                          ConsoleProperties properties) {
        this.instanceService = instanceService;
        this.gateway = gateway;
        this.metricRepository = metricRepository;
        this.instanceRepository = instanceRepository;
        this.alertService = alertService;
        this.leaseLockService = leaseLockService;
        this.properties = properties;
    }

    @Autowired(required = false)
    void setMetricsBinder(ConsoleMetricsBinder metricsBinder) {
        this.metricsBinder = metricsBinder;
    }

    @Autowired(required = false)
    void setCollectExecutor(@Qualifier("monitorCollectExecutor") Executor collectExecutor) {
        this.collectExecutor = collectExecutor;
    }

    /** 定时采集：多副本通过数据库租约锁选主，多实例并行采集并严控时间预算（≤ 70% 租约 TTL）防脑裂（Wave 2A H3）。 */
    @Scheduled(fixedDelayString = "${console.metrics.interval-ms:5000}",
            initialDelayString = "${console.metrics.initial-delay-ms:3000}")
    public void scheduledCollect() {
        long intervalMs = properties != null && properties.getMetrics() != null
                ? properties.getMetrics().getIntervalMs()
                : 5000L;
        // 租约 TTL 取 2 倍采样间隔：主副本每轮凭同一 owner_id 原地续租避免抖动，宕机后两轮内由备副本自动接管
        Duration leaseTtl = Duration.ofMillis(Math.max(4000L, intervalMs * 2L));
        if (leaseLockService != null && !leaseLockService.tryAcquire(MONITOR_LEASE_KEY, leaseTtl)) {
            return;
        }
        List<DbInstance> enabled = instanceService.enabled();
        if (enabled.isEmpty()) {
            return;
        }
        if (enabled.size() == 1 || collectExecutor == null) {
            for (DbInstance instance : enabled) {
                collectSafely(instance);
            }
            return;
        }
        long budgetMs = Math.max(2000L, (long) (leaseTtl.toMillis() * 0.7d));
        record SubmittedTask(Long instanceId, AtomicBoolean started, CompletableFuture<Void> future) {
        }
        List<SubmittedTask> submitted = new ArrayList<>(enabled.size());
        for (DbInstance instance : enabled) {
            Long id = instance.getId();
            if (id != null && !inFlightInstances.add(id)) {
                log.debug("实例上一轮采集仍在执行，本轮跳过防止线程堆积 instance={}", instance.getName());
                continue;
            }
            AtomicBoolean started = new AtomicBoolean(false);
            try {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    if (!started.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        collectSafely(instance);
                    } finally {
                        if (id != null) {
                            inFlightInstances.remove(id);
                        }
                    }
                }, collectExecutor);
                submitted.add(new SubmittedTask(id, started, future));
            } catch (RejectedExecutionException ex) {
                if (id != null) {
                    inFlightInstances.remove(id);
                }
                log.warn("监控采集线程池队列已满，跳过本轮实例采样 instance={}", instance.getName());
            }
        }
        if (submitted.isEmpty()) {
            return;
        }
        CompletableFuture<?> all = CompletableFuture.allOf(
                submitted.stream().map(SubmittedTask::future).toArray(CompletableFuture[]::new));
        try {
            all.get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // 取消尚未开始的任务并释放其占位；已在阻塞读取 Socket 的线程保留占位直至 connectTimeoutMs+readTimeoutMs 有界退出
            int unfinished = 0;
            for (SubmittedTask task : submitted) {
                if (!task.future().isDone()) {
                    unfinished++;
                    task.future().cancel(true);
                    if (task.instanceId() != null && task.started().compareAndSet(false, true)) {
                        inFlightInstances.remove(task.instanceId());
                    }
                }
            }
            log.warn("本轮监控并发采集达到租约预算 {} ms（总实例={}，未完成={}），提前结束等待以保障租约按期续期",
                    budgetMs, enabled.size(), unfinished);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            log.debug("并发采集存在单实例异常：{}", ex.getMessage());
        }
    }

    int inFlightCount() {
        return inFlightInstances.size();
    }

    private void collectSafely(DbInstance instance) {
        try {
            collectOnce(instance);
        } catch (RuntimeException ex) {
            log.debug("采集失败 instance={} err={}", instance.getName(), ex.getMessage());
        }
    }

    /** 清理已删除实例的本地状态节流缓存（Wave 2A H4）。 */
    public void evictInstanceState(Long instanceId) {
        if (instanceId != null) {
            lastPersistedAt.remove(instanceId);
        }
    }

    /** 跨副本对账：移除数据库中已不存在的实例缓存项。 */
    public int reconcileActiveInstances(Set<Long> activeIds) {
        int removed = 0;
        for (Long id : List.copyOf(lastPersistedAt.keySet())) {
            if (!activeIds.contains(id) && lastPersistedAt.remove(id) != null) {
                removed++;
            }
        }
        return removed;
    }

    /** 采集单实例：写指标 + 更新状态 + 求值告警。 */
    public MetricPoint collectOnce(DbInstance instance) {
        LocalDateTime now = LocalDateTime.now();
        String infoText;
        try {
            infoText = gateway.info(instance);
        } catch (RuntimeException ex) {
            markStatus(instance, InstanceStatus.DOWN);
            if (metricsBinder != null) {
                metricsBinder.recordInstanceDown(instance);
            }
            return null;
        }
        Map<String, Map<String, String>> sections = InfoParser.parse(infoText);
        MetricPoint point = MetricMapper.toPoint(instance.getId(), sections, now);
        metricRepository.save(point);
        markStatus(instance, InstanceStatus.UP,
                InfoParser.get(sections, "Server", "redis_version"));
        alertService.evaluate(instance.getId(), MetricMapper.metricDictionary(point, sections), now);
        if (metricsBinder != null) {
            Map<String, String> flat = InfoParser.flatten(sections);
            long coldTables = parseLong(firstNonNull(
                    flat.get("tiering_cold_sstable_count"),
                    flat.get("cold_tables"),
                    flat.get("cold_table_count")));
            double p99 = parseDouble(firstNonNull(
                    flat.get("latency_p99_ms"),
                    flat.get("p99_latency_ms")));
            metricsBinder.recordInstanceUp(instance, point, coldTables, p99);
        }
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
                    parseLong(flat.get("max_jvm_heap")),
                    parseLong(flat.get("tiering_memtable_used_bytes")),
                    parseLong(flat.get("tiering_memtable_max_bytes")),
                    parseLong(flat.get("tiering_memtable_entries")),
                    parseLong(flat.get("tiering_hot_keys")),
                    parseLong(flat.get("tiering_cold_entries")),
                    parseDouble(flat.get("tiering_hot_key_share")),
                    parseLong(flat.get("tiering_cold_sstable_count")),
                    parseLong(flat.get("tiering_cold_disk_usage_bytes")),
                    parseDouble(flat.get("tiering_cold_share")),
                    parseLong(flat.get("tiering_flush_count")),
                    parseLong(flat.get("tiering_flush_bytes")),
                    parseLong(flat.get("tiering_migration_pending")),
                    parseLong(flat.get("tiering_migration_success")),
                    parseLong(flat.get("tiering_migration_failed")),
                    parseLong(flat.get("tiering_block_cache_hits")),
                    parseLong(flat.get("tiering_block_cache_misses")),
                    parseDouble(flat.get("tiering_block_cache_hit_rate")),
                    parseLong(flat.get("tiering_block_cache_evictions")),
                    parseDouble(flat.get("latency_p50_ms")),
                    parseDouble(flat.get("latency_p95_ms")),
                    parseDouble(flat.get("latency_p99_ms")),
                    parseLong(flat.get("latency_max_ms")),
                    parseLong(flat.get("latency_samples")),
                    flat.containsKey("tiering_memtable_max_bytes"),
                    parseLong(flat.get("tiering_flush_in_progress")),
                    parseLong(flat.get("tiering_compaction_in_progress")),
                    parseLong(flat.get("tiering_compaction_count")),
                    parseLong(flat.get("tiering_compaction_input_bytes")),
                    parseLong(flat.get("tiering_compaction_output_bytes")),
                    parseDouble(flat.get("tiering_compaction_latency_ms"))));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private void markStatus(DbInstance instance, InstanceStatus status) {
        markStatus(instance, status, null);
    }

    /**
     * 状态回写：状态/版本变化立即落库；否则最多每 60 秒刷新一次
     * last_probe_at，避免 5 秒采集周期变成 5 秒一次写库。
     */
    private void markStatus(DbInstance instance, InstanceStatus status, String version) {
        LocalDateTime now = LocalDateTime.now();
        instance.setLastProbeAt(now);
        boolean changed = instance.getStatus() != status
                || (version != null && !version.equals(instance.getVersion()));
        long last = lastPersistedAt.getOrDefault(instance.getId(), 0L);
        boolean due = System.currentTimeMillis() - last >= STATUS_PERSIST_INTERVAL_MS;
        if (!changed && !due) {
            return;
        }
        instance.setStatus(status);
        if (version != null) {
            instance.setVersion(version);
        }
        instanceRepository.save(instance);
        lastPersistedAt.put(instance.getId(), System.currentTimeMillis());
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

    private static String firstNonNull(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    /** 仪表盘聚合用：批量单次 SQL 按实例返回最新一条采样，消除 N+1 查询。 */
    public Map<Long, MetricPoint> latestByInstance() {
        List<DbInstance> enabled = instanceService.enabled();
        Map<Long, MetricPoint> result = new LinkedHashMap<>();
        if (enabled.isEmpty()) {
            return result;
        }
        List<Long> ids = enabled.stream().map(DbInstance::getId).toList();
        Map<Long, MetricPoint> fetched = new LinkedHashMap<>();
        for (MetricPoint point : metricRepository.findLatestByInstanceIds(ids)) {
            fetched.put(point.getInstanceId(), point);
        }
        for (DbInstance instance : enabled) {
            MetricPoint point = fetched.get(instance.getId());
            if (point != null) {
                result.put(instance.getId(), point);
            }
        }
        return result;
    }
}