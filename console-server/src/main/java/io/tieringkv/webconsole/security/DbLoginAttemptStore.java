package io.tieringkv.webconsole.security;

import io.tieringkv.webconsole.domain.LoginAttempt;
import io.tieringkv.webconsole.repository.LoginAttemptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 数据库版多副本共享登录限流存储（M3）。
 *
 * <p>通过 {@code console.security.login-attempt-store=db} 启用；
 * 借助原子条件 UPDATE + 冲突重试，无需引入外部缓存即可跨多副本共享防爆破窗口。
 */
@Component
@ConditionalOnProperty(name = "console.security.login-attempt-store", havingValue = "db")
public class DbLoginAttemptStore implements LoginAttemptStore {

    private final LoginAttemptRepository repository;
    private final TransactionTemplate requiresNewTx;
    private final Clock clock;

    @Autowired
    public DbLoginAttemptStore(LoginAttemptRepository repository,
                               PlatformTransactionManager transactionManager) {
        this(repository, transactionManager, Clock.systemDefaultZone());
    }

    public DbLoginAttemptStore(LoginAttemptRepository repository,
                               PlatformTransactionManager transactionManager,
                               Clock clock) {
        this.repository = repository;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Override
    public boolean isBlocked(String bucketKey, int maxFailures, Duration window) {
        String key = normalizeKey(bucketKey);
        LocalDateTime now = LocalDateTime.now(clock);
        return repository.findById(key)
                .filter(attempt -> !attempt.getExpiresAt().isBefore(now))
                .map(attempt -> attempt.getFailCount() >= maxFailures)
                .orElse(false);
    }

    @Override
    public void recordFailure(String bucketKey, Duration window) {
        String key = normalizeKey(bucketKey);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = now.plus(window);

        Integer incremented = requiresNewTx.execute(status -> repository.incrementActive(key, now));
        if (incremented != null && incremented > 0) {
            return;
        }
        Integer reset = requiresNewTx.execute(status -> repository.resetExpired(key, now, expiresAt));
        if (reset != null && reset > 0) {
            return;
        }
        try {
            requiresNewTx.executeWithoutResult(status -> {
                LoginAttempt attempt = new LoginAttempt();
                attempt.setBucketKey(key);
                attempt.setFailCount(1);
                attempt.setWindowStart(now);
                attempt.setExpiresAt(expiresAt);
                attempt.setUpdatedAt(now);
                repository.saveAndFlush(attempt);
            });
        } catch (DataIntegrityViolationException concurrentInsert) {
            // 另一副本抢先写入首条失败记录，补记本次递增
            requiresNewTx.executeWithoutResult(status -> repository.incrementActive(key, now));
        }
    }

    @Override
    public void clear(String bucketKey) {
        String key = normalizeKey(bucketKey);
        requiresNewTx.executeWithoutResult(status -> {
            if (repository.existsById(key)) {
                repository.deleteById(key);
            }
        });
    }

    @Override
    public long remainingSeconds(String bucketKey, int maxFailures, Duration window) {
        String key = normalizeKey(bucketKey);
        LocalDateTime now = LocalDateTime.now(clock);
        return repository.findById(key)
                .filter(attempt -> !attempt.getExpiresAt().isBefore(now))
                .filter(attempt -> attempt.getFailCount() >= maxFailures)
                .map(attempt -> Math.max(1L, Duration.between(now, attempt.getExpiresAt()).getSeconds()))
                .orElse(0L);
    }

    private static String normalizeKey(String raw) {
        String key = raw == null ? "" : raw.trim();
        return key.length() > 160 ? key.substring(0, 160) : key;
    }
}
