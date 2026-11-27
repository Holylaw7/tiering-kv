package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.InstanceDeletedEvent;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import jakarta.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HA 跨副本本地资源对账器（Wave 2A H4）。
 *
 * <p>在多副本无状态部署下，若副本 A 处理了实例更新或删除请求，副本 B 不会收到该 HTTP 请求。
 * 本服务通过两条路径保持全副本本地资源最终一致：
 * <ul>
 *   <li>本地即时路径：监听 {@link InstanceDeletedEvent}，提交事务后立即清理本副本缓存与持久化告警态；</li>
 *   <li>跨副本周期对账路径：<b>不抢主锁</b>，每个副本独立每 30 秒对比 MySQL 存活实例清单，
 *       自动驱逐陈旧/已删实例的 RESP 连接池、Prometheus 指标序列、采集节流时间戳与备份本地锁。</li>
 * </ul>
 */
@Service
public class InstanceReconcileService {

    private static final Logger log = LoggerFactory.getLogger(InstanceReconcileService.class);
    /** 连续 DB 失败日志节流步长：首错与每 10 次失败打印一次 WARN，防止定时任务刷屏。 */
    private static final int FAILURE_LOG_STRIDE = 10;

    private final DbInstanceRepository instanceRepository;
    private final RespGateway gateway;
    private final ConsoleMetricsBinder metricsBinder;
    private final MonitorService monitorService;
    private final BackupService backupService;
    private final AlertStateStore alertStateStore;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    @Autowired
    public InstanceReconcileService(DbInstanceRepository instanceRepository,
                                    RespGateway gateway,
                                    ConsoleMetricsBinder metricsBinder,
                                    MonitorService monitorService,
                                    BackupService backupService,
                                    AlertStateStore alertStateStore) {
        this.instanceRepository = instanceRepository;
        this.gateway = gateway;
        this.metricsBinder = metricsBinder;
        this.monitorService = monitorService;
        this.backupService = backupService;
        this.alertStateStore = alertStateStore;
    }

    @EventListener
    public void onInstanceDeleted(InstanceDeletedEvent event) {
        if (event == null || event.instanceId() == null) {
            return;
        }
        Long id = event.instanceId();
        gateway.evict(id);
        if (metricsBinder != null) {
            metricsBinder.unregisterInstance(id);
        }
        if (monitorService != null) {
            monitorService.evictInstanceState(id);
        }
        if (backupService != null) {
            backupService.evictInstanceLock(id);
        }
        if (alertStateStore != null) {
            alertStateStore.clearForInstance(id);
        }
    }

    /**
     * 每个副本独立周期执行，清理已被对端副本删除或修改连接参数的本地资源。
     *
     * <p>严格区分“数据库查询异常”与“查询成功返回空表”：
     * 查询异常时保留全部现有连接池与指标并返回 {@code -1}（节流记日志，下一轮自动恢复）；
     * 仅当数据库正常返回空列表时，才执行全量本地资源驱逐。
     */
    @Scheduled(fixedDelayString = "${console.ha.reconcile-interval-ms:30000}",
            initialDelayString = "${console.ha.reconcile-initial-delay-ms:30000}")
    public int reconcileAll() {
        List<DbInstance> all;
        try {
            all = instanceRepository.findAll();
        } catch (DataAccessException | TransactionException | PersistenceException ex) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures == 1 || failures % FAILURE_LOG_STRIDE == 0) {
                log.warn("HA 跨副本对账查询实例列表失败（已连续 {} 次），保留现有连接池与指标等待下轮恢复：{}",
                        failures, ex.getClass().getSimpleName());
            }
            return -1;
        }
        int prevFailures = consecutiveFailures.getAndSet(0);
        if (prevFailures > 0) {
            log.info("HA 跨副本对账数据库连接已恢复（此前连续失败 {} 次）", prevFailures);
        }
        Map<Long, DbInstance> liveById = new LinkedHashMap<>(all.size());
        for (DbInstance inst : all) {
            if (inst.getId() != null) {
                liveById.put(inst.getId(), inst);
            }
        }
        Set<Long> activeIds = liveById.keySet();
        int cleaned = 0;

        for (Long cachedId : gateway.cachedInstanceIds()) {
            if (gateway.evictIfStale(cachedId, liveById.get(cachedId))) {
                cleaned++;
            }
        }
        if (metricsBinder != null) {
            for (Long registeredId : metricsBinder.registeredInstanceIds()) {
                if (!activeIds.contains(registeredId)) {
                    metricsBinder.unregisterInstance(registeredId);
                    cleaned++;
                }
            }
        }
        if (monitorService != null) {
            cleaned += monitorService.reconcileActiveInstances(activeIds);
        }
        if (backupService != null) {
            cleaned += backupService.reconcileInstanceLocks(activeIds);
        }
        if (cleaned > 0) {
            log.info("HA 跨副本本地资源对账完成：清理或刷新 {} 项陈旧实例资源", cleaned);
        }
        return cleaned;
    }

    int consecutiveFailureCount() {
        return consecutiveFailures.get();
    }
}
