package io.tieringkv.webconsole.security;

import java.time.Duration;

/**
 * 登录防爆破计数存储抽象（O3-A）：默认内存实现，多副本部署可无缝替换为共享缓存/数据库实现。
 */
public interface LoginAttemptStore {

    boolean isBlocked(String bucketKey, int maxFailures, Duration window);

    void recordFailure(String bucketKey, Duration window);

    void clear(String bucketKey);

    long remainingSeconds(String bucketKey, int maxFailures, Duration window);
}
