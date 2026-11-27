package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.AlertEvalState;
import io.tieringkv.webconsole.repository.AlertEvalStateRepository;
import jakarta.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警 HA 持久化状态协调器（Wave 2A H2）。
 *
 * <p>核心工程保障：
 * <ul>
 *   <li><b>原子性</b>：全部状态写操作运行在独立 {@code REQUIRES_NEW} 短事务内，通过条件 {@code UPDATE}
 *       + 主键唯一约束冲突回退实现跨副本无锁 CAS，绝不污染外层业务事务；</li>
 *   <li><b>多实例并发防重发</b>：FIRING 静默窗与 RESOLVED/零静默事件均按主键或条件更新原子抢占，
 *       即使主备租约交叠也仅允许单个副本发送通知；</li>
 *   <li><b>重启/切换无缝恢复</b>：{@code satisfied_since} 落库且长持续告警每小时自动续刷 {@code updated_at}，
 *       重启或主备切换后无缝续接持续时长，不被过期清理误删；</li>
 *   <li><b>DB 不可用保守降级</b>：MySQL 瞬断时自动降级为有界本地内存兜底（上限 10,000 键 + 至少 30s
 *       保守静默窗），防止数据库抖动触发告警风暴或拖垮监控采集。</li>
 * </ul>
 */
@Service
public class AlertStateStore {

    private static final Logger log = LoggerFactory.getLogger(AlertStateStore.class);
    private static final int MAX_LOCAL_FALLBACK_ENTRIES = 10_000;
    private static final int CONSERVATIVE_FALLBACK_SILENCE_SECONDS = 30;
    private static final long ACTIVE_TOUCH_INTERVAL_MINUTES = 60L;

    private final AlertEvalStateRepository repository;
    private final TransactionTemplate requiresNewTx;
    private final Clock clock;

    /** DB 不可用或纯内存单测时的有界本地兜底存储。 */
    private final Map<String, LocalDateTime> localSatisfied = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> localNotified = new ConcurrentHashMap<>();

    @Autowired
    public AlertStateStore(AlertEvalStateRepository repository,
                           PlatformTransactionManager transactionManager) {
        this(repository, transactionManager, Clock.systemDefaultZone());
    }

    public AlertStateStore(AlertEvalStateRepository repository,
                           PlatformTransactionManager transactionManager,
                           Clock clock) {
        this.repository = repository;
        if (transactionManager != null) {
            this.requiresNewTx = new TransactionTemplate(transactionManager);
            this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } else {
            this.requiresNewTx = null;
        }
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /** 仅用于纯内存单元测试（无 DB 依赖）。 */
    public static AlertStateStore inMemoryOnly(Clock clock) {
        return new AlertStateStore(null, null, clock);
    }

    public static String evalKey(Long ruleId, Long instanceId) {
        return "eval:" + ruleId + ":" + instanceId;
    }

    public static String notifyKey(Long channelId, Long ruleId, Long instanceId) {
        return "notify:" + channelId + ":" + ruleId + ":" + instanceId;
    }

    public static String resolvedEventKey(Long channelId, Long eventId) {
        return "resolved:" + channelId + ":" + eventId;
    }

    public static String firingEventKey(Long channelId, Long eventId) {
        return "event:" + channelId + ":" + eventId;
    }

    /** 记录或读取首次满足阈值时间：重启/主备切换后自动从 MySQL 续接持续时长。 */
    public LocalDateTime recordOrGetSatisfiedSince(Long ruleId, Long instanceId, LocalDateTime rawNow) {
        String key = evalKey(ruleId, instanceId);
        LocalDateTime now = truncateMillis(rawNow == null ? LocalDateTime.now(clock) : rawNow);
        if (!isDbBacked()) {
            return putBoundedIfAbsent(localSatisfied, key, now);
        }
        try {
            Optional<LocalDateTime> existing = requiresNewTx.execute(status -> {
                Optional<AlertEvalState> found = repository.findById(key);
                if (found.isPresent() && found.get().getSatisfiedSince() != null) {
                    AlertEvalState row = found.get();
                    if (row.getUpdatedAt() == null
                            || row.getUpdatedAt().isBefore(now.minusMinutes(ACTIVE_TOUCH_INTERVAL_MINUTES))) {
                        repository.touchUpdatedAt(key, now, now.minusMinutes(ACTIVE_TOUCH_INTERVAL_MINUTES));
                    }
                    return Optional.of(row.getSatisfiedSince());
                }
                return Optional.empty();
            });
            if (existing != null && existing.isPresent()) {
                return existing.get();
            }
            try {
                LocalDateTime inserted = requiresNewTx.execute(status -> {
                    repository.insertRaw(key, now, null, now);
                    return now;
                });
                return inserted != null ? inserted : now;
            } catch (RuntimeException ex) {
                if (isContentionOrLockFailure(ex)) {
                    // 另一副本抢先写入或锁竞争，读取胜出者的首次满足时间保持多副本一致
                    LocalDateTime winner = requiresNewTx.execute(status ->
                            repository.findById(key).map(AlertEvalState::getSatisfiedSince).orElse(now));
                    return winner != null ? winner : now;
                }
                throw ex;
            }
        } catch (DataAccessException | TransactionException | PersistenceException ex) {
            if (isContentionOrLockFailure(ex)) {
                return now;
            }
            log.warn("告警持续时长状态库不可用，降级为本地有界缓存 key={} err={}", key, ex.getClass().getSimpleName());
            return putBoundedIfAbsent(localSatisfied, key, now);
        }
    }

    /** 指标恢复或手动关闭告警后清除持续时长状态。 */
    public void clearSatisfiedSince(Long ruleId, Long instanceId) {
        if (ruleId == null || instanceId == null) {
            return;
        }
        String key = evalKey(ruleId, instanceId);
        localSatisfied.remove(key);
        if (!isDbBacked()) {
            return;
        }
        try {
            requiresNewTx.executeWithoutResult(status -> {
                if (repository.existsById(key)) {
                    repository.deleteById(key);
                }
            });
        } catch (DataAccessException | TransactionException | PersistenceException ex) {
            log.warn("清理告警持续状态失败（将在过期任务中回收） key={} err={}", key, ex.getClass().getSimpleName());
        }
    }

    /**
     * 原子抢占 Webhook 发送权（跨副本并发防重发）：
     * <ul>
     *   <li>{@code RESOLVED}：先清除静默窗口以便后续新故障立即触达，再按 {@code channelId:eventId}
     *       原子占位确保同一恢复事件仅被单个副本推送一次；</li>
     *   <li>{@code FIRING}（静默 &gt; 0）：单条条件 SQL CAS 更新 {@code last_notified_at} 或唯一键插入，
     *       多副本并发竞态下仅 1 个副本返回 {@code true}；</li>
     *   <li>{@code FIRING}（静默 = 0）：按 {@code channelId:eventId} 原子去重，防止租约交叠重发同一事件。</li>
     * </ul>
     */
    public boolean tryClaimWebhookDelivery(Long channelId, Long ruleId, Long instanceId,
                                           Long eventId, int silenceSeconds, boolean resolved) {
        LocalDateTime now = truncateMillis(LocalDateTime.now(clock));
        String silenceKey = notifyKey(channelId, ruleId, instanceId);
        int effectiveSilence = Math.max(0, silenceSeconds);

        if (!isDbBacked()) {
            return claimLocally(silenceKey, channelId, eventId, effectiveSilence, resolved, now);
        }
        try {
            if (resolved) {
                requiresNewTx.executeWithoutResult(status -> {
                    if (repository.existsById(silenceKey)) {
                        repository.deleteById(silenceKey);
                    }
                });
                localNotified.remove(silenceKey);
                if (eventId == null) {
                    return true;
                }
                return tryInsertOnce(resolvedEventKey(channelId, eventId), now);
            }
            if (effectiveSilence == 0) {
                if (eventId == null) {
                    return true;
                }
                return tryInsertOnce(firingEventKey(channelId, eventId), now);
            }
            LocalDateTime cutoff = now.minusSeconds(effectiveSilence);
            Integer updated = requiresNewTx.execute(status ->
                    repository.tryClaimNotificationWindow(silenceKey, now, cutoff));
            if (updated != null && updated > 0) {
                return true;
            }
            Boolean exists = requiresNewTx.execute(status -> repository.existsById(silenceKey));
            if (Boolean.TRUE.equals(exists)) {
                return false;
            }
            return tryInsertOnce(silenceKey, now);
        } catch (DataAccessException | TransactionException | PersistenceException ex) {
            // 1. 唯一键冲突、死锁（MySQL 1213）、锁等待超时（MySQL 1205 / QueryTimeoutException）均视为并发竞争未胜出，
            //    严禁降级到本地缓存导致多副本重复发送
            if (isContentionOrLockFailure(ex)) {
                log.debug("告警通知状态并发/锁竞争未胜出 key={} err={}", silenceKey, ex.getClass().getSimpleName());
                return false;
            }
            // 2. 仅在明确属于数据库连接/资源不可用时，才启用有界保守本地静默降级
            if (isDatabaseUnavailable(ex)) {
                log.error("告警通知状态库连接不可用，启用保守本地静默降级防止告警风暴 key={} err={}",
                        silenceKey, ex.getClass().getSimpleName());
                int conservativeSilence = resolved ? 0 : Math.max(effectiveSilence, CONSERVATIVE_FALLBACK_SILENCE_SECONDS);
                return claimLocally(silenceKey, channelId, eventId, conservativeSilence, resolved, now);
            }
            // 3. 其余未知数据库异常一律保守失败（fail-closed），杜绝误降级重发
            log.error("告警通知状态操作异常，按保守策略放弃本次发送 key={} err={}",
                    silenceKey, ex.getClass().getSimpleName());
            return false;
        }
    }

    /** 规则删除时清理关联状态行，避免孤儿数据累积。 */
    public void clearForRule(Long ruleId) {
        if (ruleId == null) {
            return;
        }
        String ruleSegment = String.valueOf(ruleId);
        String evalPrefix = "eval:" + ruleSegment + ":";
        localSatisfied.keySet().removeIf(k -> k.startsWith(evalPrefix));
        localNotified.keySet().removeIf(k -> matchesNotifyRule(k, ruleSegment));
        if (!isDbBacked()) {
            return;
        }
        try {
            requiresNewTx.executeWithoutResult(status -> {
                repository.deleteByKeyPattern(evalPrefix + "%");
                repository.deleteByKeyPattern("notify:%:" + ruleSegment + ":%");
            });
        } catch (DataAccessException | TransactionException | PersistenceException ex) {
            log.warn("按规则清理告警状态失败 ruleId={} err={}", ruleId, ex.getClass().getSimpleName());
        }
    }

    /** 通知渠道删除时清理关联静默与防重状态行。 */
    public void clearForChannel(Long channelId) {
        if (channelId == null) {
            return;
        }
        String nPrefix = "notify:" + channelId + ":";
        String rPrefix = "resolved:" + channelId + ":";
        String ePrefix = "event:" + channelId + ":";
        localNotified.keySet().removeIf(k -> k.startsWith(nPrefix) || k.startsWith(rPrefix) || k.startsWith(ePrefix));
        if (!isDbBacked()) {
            return;
        }
        try {
            requiresNewTx.executeWithoutResult(status -> {
                repository.deleteByKeyPattern(nPrefix + "%");
                repository.deleteByKeyPattern(rPrefix + "%");
                repository.deleteByKeyPattern(ePrefix + "%");
            });
        } catch (DataAccessException | TransactionException | PersistenceException ex) {
            log.warn("按渠道清理告警状态失败 channelId={} err={}", channelId, ex.getClass().getSimpleName());
        }
    }

    /** 实例删除时清理该实例关联的持续计时与静默状态行。 */
    public void clearForInstance(Long instanceId) {
        if (instanceId == null) {
            return;
        }
        String suffix = ":" + instanceId;
        localSatisfied.keySet().removeIf(k -> k.endsWith(suffix));
        localNotified.keySet().removeIf(k -> k.startsWith("notify:") && k.endsWith(suffix));
        if (!isDbBacked()) {
            return;
        }
        try {
            requiresNewTx.executeWithoutResult(status -> {
                repository.deleteByKeyPattern("eval:%:" + instanceId);
                repository.deleteByKeyPattern("notify:%:" + instanceId);
            });
        } catch (DataAccessException | TransactionException | PersistenceException ex) {
            log.warn("按实例清理告警状态失败 instanceId={} err={}", instanceId, ex.getClass().getSimpleName());
        }
    }

    /** 定时清理超过保留期限的过期状态行（由 RetentionService 选主调用）。 */
    public int purgeStaleBefore(LocalDateTime cutoff) {
        localSatisfied.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        localNotified.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        if (!isDbBacked()) {
            return 0;
        }
        try {
            Integer removed = requiresNewTx.execute(status -> repository.deleteStaleBefore(truncateMillis(cutoff)));
            return removed == null ? 0 : removed;
        } catch (DataAccessException | TransactionException | PersistenceException ex) {
            log.warn("清理过期告警状态失败 err={}", ex.getClass().getSimpleName());
            return 0;
        }
    }

    private boolean tryInsertOnce(String key, LocalDateTime now) {
        try {
            Boolean won = requiresNewTx.execute(status -> {
                repository.insertRaw(key, null, now, now);
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(won);
        } catch (RuntimeException ex) {
            if (isContentionOrLockFailure(ex)) {
                return false;
            }
            throw ex;
        }
    }

    /** 精确校验 notify:{channelId}:{ruleId}:{instanceId} 的第 3 段 ruleId，防止与 channelId 冲突。 */
    private static boolean matchesNotifyRule(String key, String ruleSegment) {
        if (key == null || !key.startsWith("notify:")) {
            return false;
        }
        String[] parts = key.split(":", -1);
        return parts.length == 4 && ruleSegment.equals(parts[2]);
    }

    /** 沿异常链精确识别唯一键冲突（1062）、死锁（1213）与锁等待超时（1205 / LockTimeout / QueryTimeout）。 */
    static boolean isContentionOrLockFailure(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth++ < 16) {
            if (cur instanceof DataIntegrityViolationException
                    || cur instanceof ConcurrencyFailureException
                    || cur instanceof QueryTimeoutException
                    || cur instanceof jakarta.persistence.LockTimeoutException
                    || cur instanceof jakarta.persistence.PessimisticLockException
                    || cur instanceof jakarta.persistence.OptimisticLockException
                    || cur instanceof SQLIntegrityConstraintViolationException
                    || cur instanceof SQLTransactionRollbackException) {
                return true;
            }
            if (cur instanceof SQLException sqlEx) {
                int code = sqlEx.getErrorCode();
                String sqlState = sqlEx.getSQLState();
                // MySQL 1062(重复键), 1205(InnoDB 锁等待超时), 1213(InnoDB 死锁); H2 23505/40001/50200
                if (code == 1062 || code == 1205 || code == 1213 || code == 23505 || code == 50200
                        || "23000".equals(sqlState) || "23505".equals(sqlState) || "40001".equals(sqlState)) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 沿异常链精确识别真实数据库连接/资源不可用故障。 */
    static boolean isDatabaseUnavailable(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth++ < 16) {
            if (cur instanceof DataAccessResourceFailureException
                    || cur instanceof CannotGetJdbcConnectionException
                    || cur instanceof CannotCreateTransactionException
                    || cur instanceof TransientDataAccessResourceException
                    || cur instanceof SQLTransientConnectionException
                    || cur instanceof SQLNonTransientConnectionException
                    || cur instanceof ConnectException
                    || cur instanceof SocketTimeoutException) {
                return true;
            }
            if (cur instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private boolean claimLocally(String silenceKey, Long channelId, Long eventId,
                                 int silenceSeconds, boolean resolved, LocalDateTime now) {
        if (resolved) {
            localNotified.remove(silenceKey);
            if (eventId == null) {
                return true;
            }
            String dedupKey = resolvedEventKey(channelId, eventId);
            enforceLocalCap(localNotified);
            return localNotified.putIfAbsent(dedupKey, now) == null;
        }
        if (silenceSeconds <= 0) {
            if (eventId == null) {
                return true;
            }
            String dedupKey = firingEventKey(channelId, eventId);
            enforceLocalCap(localNotified);
            return localNotified.putIfAbsent(dedupKey, now) == null;
        }
        enforceLocalCap(localNotified);
        boolean[] claimed = {false};
        localNotified.compute(silenceKey, (k, previous) -> {
            if (previous != null && Duration.between(previous, now).getSeconds() < silenceSeconds) {
                return previous;
            }
            claimed[0] = true;
            return now;
        });
        return claimed[0];
    }

    private boolean isDbBacked() {
        return repository != null && requiresNewTx != null;
    }

    private static LocalDateTime putBoundedIfAbsent(Map<String, LocalDateTime> map,
                                                    String key, LocalDateTime value) {
        enforceLocalCap(map);
        return map.computeIfAbsent(key, ignored -> value);
    }

    private static void enforceLocalCap(Map<String, LocalDateTime> map) {
        if (map.size() >= MAX_LOCAL_FALLBACK_ENTRIES) {
            map.clear();
        }
    }

    private static LocalDateTime truncateMillis(LocalDateTime dt) {
        return dt.truncatedTo(ChronoUnit.MILLIS);
    }
}
