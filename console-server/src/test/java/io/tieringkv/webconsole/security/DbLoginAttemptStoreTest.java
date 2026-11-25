package io.tieringkv.webconsole.security;

import io.tieringkv.webconsole.repository.LoginAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** M3：数据库版多副本共享登录限流验证（账号 + IP 双维度跨副本一致性与窗口过期重置）。 */
@SpringBootTest
@ActiveProfiles("test")
class DbLoginAttemptStoreTest {

    @Autowired
    private LoginAttemptRepository loginAttemptRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AtomicReference<Instant> currentInstant;
    private Clock mutableClock;

    @BeforeEach
    void setUp() {
        loginAttemptRepository.deleteAll();
        currentInstant = new AtomicReference<>(Instant.parse("2026-11-25T10:00:00Z"));
        mutableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return currentInstant.get();
            }
        };
    }

    @Test
    void sharesUserAndIpFailureBucketsAcrossTwoReplicasAndResetsOnSuccessOrExpiry() {
        DbLoginAttemptStore storeA = new DbLoginAttemptStore(
                loginAttemptRepository, transactionManager, mutableClock);
        DbLoginAttemptStore storeB = new DbLoginAttemptStore(
                loginAttemptRepository, transactionManager, mutableClock);

        LoginAttemptGuard replicaA = new LoginAttemptGuard(storeA, 6);
        LoginAttemptGuard replicaB = new LoginAttemptGuard(storeB, 6);

        // 账号维度：副本 A 失败 3 次 + 副本 B 失败 2 次 = 累计 5 次，两副本同时锁定
        for (int i = 0; i < 3; i++) {
            replicaA.recordFailure("ha_user");
        }
        assertThat(replicaB.blocked("ha_user")).isFalse();
        replicaB.recordFailure("ha_user");
        replicaB.recordFailure("ha_user");

        assertThat(replicaA.blocked("ha_user")).isTrue();
        assertThat(replicaB.blocked("ha_user")).isTrue();
        assertThat(replicaA.remainingSeconds("ha_user")).isGreaterThan(0L);

        // 副本 B 登录成功清理后，副本 A 立即解除锁定
        replicaB.recordSuccess("ha_user");
        assertThat(replicaA.blocked("ha_user")).isFalse();
        assertThat(replicaA.remainingSeconds("ha_user")).isZero();

        // IP 维度：跨副本轮换用户名累计 6 次失败，两副本同时拦截该来源 IP
        for (int i = 0; i < 3; i++) {
            replicaA.recordIpFailure("203.0.113.42");
            replicaB.recordIpFailure("203.0.113.42");
        }
        assertThat(replicaA.blockedIp("203.0.113.42")).isTrue();
        assertThat(replicaB.blockedIp("203.0.113.42")).isTrue();
        assertThat(replicaB.remainingIpSeconds("203.0.113.42")).isGreaterThan(0L);

        // 超过 5 分钟窗口后自动解除，再次失败重新从 1 计数
        currentInstant.set(currentInstant.get().plusSeconds(301));
        assertThat(replicaA.blockedIp("203.0.113.42")).isFalse();
        replicaA.recordIpFailure("203.0.113.42");
        assertThat(replicaB.blockedIp("203.0.113.42")).isFalse();
    }
}
