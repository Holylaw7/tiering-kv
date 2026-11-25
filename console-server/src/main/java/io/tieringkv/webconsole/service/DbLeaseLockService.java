package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.ConsoleLease;
import io.tieringkv.webconsole.repository.ConsoleLeaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 基于关系数据库的轻量租约锁（M3）：
 * <ul>
 *   <li>零新外部中间件，复用既有 MySQL 8 即可支持控制面多副本部署；</li>
 *   <li>采用条件 UPDATE + 主键冲突回退实现原子抢锁与同节点可重入续租；</li>
 *   <li>持有节点宕机后租约到期自动失效，备节点可无缝接管定时采集与清理任务。</li>
 * </ul>
 */
@Service
public class DbLeaseLockService {

    private static final Logger log = LoggerFactory.getLogger(DbLeaseLockService.class);

    private final ConsoleLeaseRepository repository;
    private final TransactionTemplate requiresNewTx;
    private final String ownerId;
    private final Clock clock;

    @Autowired
    public DbLeaseLockService(ConsoleLeaseRepository repository,
                              PlatformTransactionManager transactionManager) {
        this(repository, transactionManager, defaultOwnerId(), Clock.systemDefaultZone());
    }

    public DbLeaseLockService(ConsoleLeaseRepository repository,
                              PlatformTransactionManager transactionManager,
                              String ownerId,
                              Clock clock) {
        this.repository = repository;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ownerId = normalizeOwner(ownerId);
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public String ownerId() {
        return ownerId;
    }

    /** 尝试获取或续租指定 key 的租约锁；成功返回 true，已被其他副本持有返回 false。 */
    public boolean tryAcquire(String leaseKey, Duration ttl) {
        if (leaseKey == null || leaseKey.isBlank()) {
            throw new IllegalArgumentException("leaseKey must not be blank");
        }
        Duration effectiveTtl = (ttl == null || ttl.isNegative() || ttl.isZero())
                ? Duration.ofSeconds(5)
                : ttl;
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime until = now.plus(effectiveTtl);

        Integer updated = requiresNewTx.execute(status ->
                repository.tryAcquireOrRenew(leaseKey, ownerId, until, now));
        if (updated != null && updated > 0) {
            return true;
        }
        try {
            Boolean inserted = requiresNewTx.execute(status -> {
                if (repository.existsById(leaseKey)) {
                    return Boolean.FALSE;
                }
                ConsoleLease lease = new ConsoleLease();
                lease.setLeaseKey(leaseKey);
                lease.setOwnerId(ownerId);
                lease.setLeaseUntil(until);
                lease.setUpdatedAt(now);
                repository.saveAndFlush(lease);
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(inserted);
        } catch (DataIntegrityViolationException concurrentInsert) {
            // 另一副本抢先插入相同 leaseKey，属于正常竞争
            log.debug("租约锁竞争未命中 leaseKey={} owner={}", leaseKey, ownerId);
            return false;
        }
    }

    /** 仅当锁仍归当前副本所有时主动释放（用于短临界区，如备份/恢复提交互斥）。 */
    public void release(String leaseKey) {
        if (leaseKey == null || leaseKey.isBlank()) {
            return;
        }
        try {
            requiresNewTx.executeWithoutResult(status -> repository.release(leaseKey, ownerId));
        } catch (RuntimeException ex) {
            log.warn("释放租约锁失败 leaseKey={} owner={}", leaseKey, ownerId, ex);
        }
    }

    private static String defaultOwnerId() {
        String host = "console";
        try {
            String resolved = InetAddress.getLocalHost().getHostName();
            if (resolved != null && !resolved.isBlank()) {
                host = resolved.trim();
            }
        } catch (Exception ignored) {
            // 容器无反向 DNS 时回退默认前缀
        }
        if (host.length() > 48) {
            host = host.substring(0, 48);
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String normalizeOwner(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultOwnerId();
        }
        String trimmed = raw.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }
}
