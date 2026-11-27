package io.tieringkv.webconsole.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.tieringkv.webconsole.domain.AlertStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.MetricPoint;
import io.tieringkv.webconsole.repository.AlertEventRepository;
import io.tieringkv.webconsole.service.RespGateway;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Micrometer / Prometheus 自定义业务指标绑定器（v6.4.0 D2）。
 *
 * <p>零额外依赖直接桥接既有 {@link MeterRegistry} 至 {@code /actuator/prometheus}，
 * 暴露低基数的被纳管实例水位、RESP 连接池治理、活跃告警及备份/恢复/审计计数指标。
 */
@Component
public class ConsoleMetricsBinder {

    private final MeterRegistry registry;
    private final RespGateway gateway;
    private final AlertEventRepository alertEventRepository;
    private final Map<Long, InstanceMetricState> instanceStates = new ConcurrentHashMap<>();

    public ConsoleMetricsBinder(MeterRegistry registry,
                                RespGateway gateway,
                                AlertEventRepository alertEventRepository) {
        this.registry = registry;
        this.gateway = gateway;
        this.alertEventRepository = alertEventRepository;
        registerGlobalGauges();
    }

    private void registerGlobalGauges() {
        Gauge.builder("tieringkv_console_pool_active", gateway, RespGateway::totalActiveConnections)
                .description("Current active borrowed RESP connections across all managed instances")
                .register(registry);
        Gauge.builder("tieringkv_console_pool_idle", gateway, RespGateway::totalIdleConnections)
                .description("Current idle pooled RESP connections across all managed instances")
                .register(registry);
        Gauge.builder("tieringkv_console_pool_max", gateway, RespGateway::totalMaxConnections)
                .description("Configured maximum RESP connection pool capacity across active pools")
                .register(registry);
        Gauge.builder("tieringkv_console_pool_borrow_timeouts_total", gateway, RespGateway::totalBorrowTimeouts)
                .description("Cumulative RESP connection pool borrow timeout count")
                .register(registry);
        Gauge.builder("tieringkv_console_alerts_firing", this, ConsoleMetricsBinder::firingAlertCount)
                .description("Current number of firing alerts in the operations console")
                .register(registry);
    }

    private double firingAlertCount() {
        try {
            return alertEventRepository.countByStatus(AlertStatus.FIRING);
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    /** 刷新单个在线实例的观测指标（由 MonitorService 周期采集调用）。 */
    public void recordInstanceUp(DbInstance instance, MetricPoint point,
                                 long coldTables, double p99LatencyMs) {
        if (instance == null || instance.getId() == null) {
            return;
        }
        InstanceMetricState state = stateFor(instance);
        state.up.set(1);
        long usedMem = point.getUsedMemory() == null ? 0L : point.getUsedMemory();
        long maxMem = point.getMaxMemory() == null ? 0L : point.getMaxMemory();
        double ratio = maxMem > 0L ? (double) usedMem / maxMem : 0.0d;
        double ops = point.getOpsPerSec() != null ? point.getOpsPerSec()
                : (point.getQps() != null ? point.getQps() : 0.0d);
        long keys = point.getTotalKeys() == null ? 0L : point.getTotalKeys();
        state.memoryUsedBytes.set(usedMem);
        state.memoryRatio.set(ratio);
        state.opsPerSec.set(ops);
        state.keysTotal.set(keys);
        state.coldTables.set(Math.max(0L, coldTables));
        state.p99LatencyMs.set(Math.max(0.0d, p99LatencyMs));
    }

    /** 标记实例不可达（保留历史指标序列但将 up 置 0）。 */
    public void recordInstanceDown(DbInstance instance) {
        if (instance == null || instance.getId() == null) {
            return;
        }
        InstanceMetricState state = stateFor(instance);
        state.up.set(0);
    }

    /** 注销已删除实例的指标序列，防止 Prometheus 高基数与内存泄漏。 */
    public void unregisterInstance(Long instanceId) {
        if (instanceId == null) {
            return;
        }
        InstanceMetricState removed = instanceStates.remove(instanceId);
        if (removed != null) {
            for (Meter.Id meterId : removed.registeredIds) {
                registry.remove(meterId);
            }
        }
    }

    public void recordBackup(String status) {
        Counter.builder("tieringkv_console_backups_total")
                .description("Cumulative logical backup executions by terminal status")
                .tag("status", safeTag(status))
                .register(registry)
                .increment();
    }

    public void recordRestore(String status) {
        Counter.builder("tieringkv_console_restores_total")
                .description("Cumulative logical restore executions by terminal status")
                .tag("status", safeTag(status))
                .register(registry)
                .increment();
    }

    public void recordAudit(String action, boolean success) {
        Counter.builder("tieringkv_console_audit_events_total")
                .description("Cumulative audited control-plane operations by action and outcome")
                .tag("action", safeTag(action))
                .tag("success", Boolean.toString(success))
                .register(registry)
                .increment();
    }

    private InstanceMetricState stateFor(DbInstance instance) {
        Long id = instance.getId();
        String name = instance.getName() == null ? "instance-" + id : instance.getName();
        return instanceStates.compute(id, (key, existing) -> {
            if (existing != null && existing.instanceName.equals(name)) {
                return existing;
            }
            if (existing != null) {
                for (Meter.Id meterId : existing.registeredIds) {
                    registry.remove(meterId);
                }
            }
            InstanceMetricState created = new InstanceMetricState(name);
            Tags tags = Tags.of("instance_id", String.valueOf(id), "instance_name", name);
            created.registeredIds.add(Gauge.builder("tieringkv_instance_up", created, s -> s.up.get())
                    .description("Whether the managed Tiering-KV instance is reachable (1=UP, 0=DOWN)")
                    .tags(tags).register(registry).getId());
            created.registeredIds.add(Gauge.builder("tieringkv_instance_memory_used_bytes",
                            created, s -> s.memoryUsedBytes.get())
                    .description("Managed instance hot-tier used memory in bytes")
                    .tags(tags).register(registry).getId());
            created.registeredIds.add(Gauge.builder("tieringkv_instance_memory_ratio",
                            created, s -> s.memoryRatio.get())
                    .description("Managed instance hot-tier memory utilization ratio [0..1]")
                    .tags(tags).register(registry).getId());
            created.registeredIds.add(Gauge.builder("tieringkv_instance_ops_per_sec",
                            created, s -> s.opsPerSec.get())
                    .description("Managed instance instantaneous operations per second")
                    .tags(tags).register(registry).getId());
            created.registeredIds.add(Gauge.builder("tieringkv_instance_keys_total",
                            created, s -> s.keysTotal.get())
                    .description("Managed instance total key count")
                    .tags(tags).register(registry).getId());
            created.registeredIds.add(Gauge.builder("tieringkv_instance_cold_tables",
                            created, s -> s.coldTables.get())
                    .description("Managed instance cold-tier SSTable count")
                    .tags(tags).register(registry).getId());
            created.registeredIds.add(Gauge.builder("tieringkv_instance_p99_latency_ms",
                            created, s -> s.p99LatencyMs.get())
                    .description("Managed instance reported P99 command latency in milliseconds")
                    .tags(tags).register(registry).getId());
            return created;
        });
    }

    private static String safeTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        return raw.length() > 48 ? raw.substring(0, 48) : raw;
    }

    private static final class InstanceMetricState {
        private final String instanceName;
        private final AtomicInteger up = new AtomicInteger(0);
        private final AtomicLong memoryUsedBytes = new AtomicLong(0L);
        private final AtomicReference<Double> memoryRatio = new AtomicReference<>(0.0d);
        private final AtomicReference<Double> opsPerSec = new AtomicReference<>(0.0d);
        private final AtomicLong keysTotal = new AtomicLong(0L);
        private final AtomicLong coldTables = new AtomicLong(0L);
        private final AtomicReference<Double> p99LatencyMs = new AtomicReference<>(0.0d);
        private final List<Meter.Id> registeredIds = new ArrayList<>();

        private InstanceMetricState(String instanceName) {
            this.instanceName = instanceName;
        }
    }
}
