package io.tieringkv.webconsole.security;

import io.tieringkv.webconsole.config.ConsoleProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败双维度限流（O3-A）：
 * <ul>
 *   <li>账号维度：5 分钟内连续失败 5 次锁定；</li>
 *   <li>来源 IP 维度：5 分钟内累计失败默认 20 次锁定，防止轮换用户名撞库。</li>
 * </ul>
 *
 * <p>计数存储通过 {@link LoginAttemptStore} 抽象，默认使用有界内存实现。
 */
@Component
public class LoginAttemptGuard {

    private static final int MAX_USER_FAILURES = 5;
    private static final int DEFAULT_IP_MAX_FAILURES = 20;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int MAX_TRACKED_BUCKETS = 10_000;

    private final LoginAttemptStore store;
    private final int ipMaxFailures;

    public LoginAttemptGuard() {
        this(new InMemoryLoginAttemptStore(MAX_TRACKED_BUCKETS), DEFAULT_IP_MAX_FAILURES);
    }

    public LoginAttemptGuard(ConsoleProperties properties) {
        this(new InMemoryLoginAttemptStore(MAX_TRACKED_BUCKETS),
                properties != null && properties.getSecurity() != null
                        ? Math.max(5, properties.getSecurity().getIpMaxFailures())
                        : DEFAULT_IP_MAX_FAILURES);
    }

    @Autowired
    public LoginAttemptGuard(ConsoleProperties properties, ObjectProvider<LoginAttemptStore> storeProvider) {
        this(resolveStore(storeProvider),
                properties != null && properties.getSecurity() != null
                        ? Math.max(5, properties.getSecurity().getIpMaxFailures())
                        : DEFAULT_IP_MAX_FAILURES);
    }

    private static LoginAttemptStore resolveStore(ObjectProvider<LoginAttemptStore> provider) {
        LoginAttemptStore provided = provider == null ? null : provider.getIfAvailable();
        return provided != null ? provided : new InMemoryLoginAttemptStore(MAX_TRACKED_BUCKETS);
    }

    public LoginAttemptGuard(LoginAttemptStore store, int ipMaxFailures) {
        this.store = store;
        this.ipMaxFailures = Math.max(5, ipMaxFailures);
    }

    public boolean blocked(String username) {
        return store.isBlocked(userKey(username), MAX_USER_FAILURES, WINDOW);
    }

    public void recordFailure(String username) {
        store.recordFailure(userKey(username), WINDOW);
    }

    public void recordSuccess(String username) {
        store.clear(userKey(username));
    }

    /** 剩余账号锁定时间（秒），用于提示。 */
    public long remainingSeconds(String username) {
        return store.remainingSeconds(userKey(username), MAX_USER_FAILURES, WINDOW);
    }

    /** O3-A：来源 IP 是否已触发防撞库限流。 */
    public boolean blockedIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return false;
        }
        return store.isBlocked(ipKey(clientIp), ipMaxFailures, WINDOW);
    }

    /** O3-A：记录来源 IP 登录失败。 */
    public void recordIpFailure(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        store.recordFailure(ipKey(clientIp), WINDOW);
    }

    /** O3-A：来源 IP 剩余锁定时间（秒）。 */
    public long remainingIpSeconds(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return 0;
        }
        return store.remainingSeconds(ipKey(clientIp), ipMaxFailures, WINDOW);
    }

    private String userKey(String username) {
        return "u:" + (username == null ? "" : username.trim().toLowerCase());
    }

    private String ipKey(String clientIp) {
        return "ip:" + clientIp.trim().toLowerCase();
    }

    /** 默认单机有界内存存储实现。 */
    static final class InMemoryLoginAttemptStore implements LoginAttemptStore {

        private final int maxBuckets;
        private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

        InMemoryLoginAttemptStore(int maxBuckets) {
            this.maxBuckets = maxBuckets;
        }

        @Override
        public boolean isBlocked(String bucketKey, int maxFailures, Duration window) {
            Deque<Instant> attempts = failures.get(bucketKey);
            if (attempts == null) {
                return false;
            }
            synchronized (attempts) {
                prune(attempts, window);
                return attempts.size() >= maxFailures;
            }
        }

        @Override
        public void recordFailure(String bucketKey, Duration window) {
            if (failures.size() >= maxBuckets && !failures.containsKey(bucketKey)) {
                pruneMap(window);
                if (failures.size() >= maxBuckets) {
                    Iterator<String> it = failures.keySet().iterator();
                    while (failures.size() >= maxBuckets && it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
            }
            Deque<Instant> attempts = failures.computeIfAbsent(bucketKey, ignored -> new ArrayDeque<>());
            synchronized (attempts) {
                prune(attempts, window);
                attempts.addLast(Instant.now());
            }
        }

        @Override
        public void clear(String bucketKey) {
            failures.remove(bucketKey);
        }

        @Override
        public long remainingSeconds(String bucketKey, int maxFailures, Duration window) {
            Deque<Instant> attempts = failures.get(bucketKey);
            if (attempts == null || attempts.isEmpty()) {
                return 0;
            }
            synchronized (attempts) {
                prune(attempts, window);
                if (attempts.size() < maxFailures) {
                    return 0;
                }
                Instant first = attempts.peekFirst();
                long left = window.getSeconds() - Duration.between(first, Instant.now()).getSeconds();
                return Math.max(1, left);
            }
        }

        private void pruneMap(Duration window) {
            failures.entrySet().removeIf(entry -> {
                Deque<Instant> deque = entry.getValue();
                synchronized (deque) {
                    prune(deque, window);
                    return deque.isEmpty();
                }
            });
        }

        private void prune(Deque<Instant> attempts, Duration window) {
            Instant threshold = Instant.now().minus(window);
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(threshold)) {
                attempts.pollFirst();
            }
        }
    }
}
