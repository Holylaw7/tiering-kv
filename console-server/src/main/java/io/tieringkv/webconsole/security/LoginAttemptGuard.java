package io.tieringkv.webconsole.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：同一账号在窗口期内失败次数超限后拒绝登录。
 *
 * <p>内存实现（单实例部署足够）；多副本部署应换成共享存储（Redis/MySQL）。
 */
@Component
public class LoginAttemptGuard {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public boolean blocked(String username) {
        Deque<Instant> attempts = failures.get(key(username));
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            prune(attempts);
            return attempts.size() >= MAX_FAILURES;
        }
    }

    public void recordFailure(String username) {
        Deque<Instant> attempts = failures.computeIfAbsent(key(username),
                ignored -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts);
            attempts.addLast(Instant.now());
        }
    }

    public void recordSuccess(String username) {
        failures.remove(key(username));
    }

    /** 剩余锁定时间（秒），用于提示。 */
    public long remainingSeconds(String username) {
        Deque<Instant> attempts = failures.get(key(username));
        if (attempts == null || attempts.isEmpty()) {
            return 0;
        }
        synchronized (attempts) {
            prune(attempts);
            if (attempts.size() < MAX_FAILURES) {
                return 0;
            }
            Instant first = attempts.peekFirst();
            long left = WINDOW.getSeconds() - Duration.between(first, Instant.now()).getSeconds();
            return Math.max(1, left);
        }
    }

    private void prune(Deque<Instant> attempts) {
        Instant threshold = Instant.now().minus(WINDOW);
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(threshold)) {
            attempts.pollFirst();
        }
    }

    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
