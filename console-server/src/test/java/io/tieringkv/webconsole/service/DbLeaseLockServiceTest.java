package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.repository.ConsoleLeaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** M3：MySQL 租约锁互斥、续租、宕机到期自动接管与释放安全验证。 */
@SpringBootTest
@ActiveProfiles("test")
class DbLeaseLockServiceTest {

    @Autowired
    private ConsoleLeaseRepository leaseRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AtomicReference<Instant> currentInstant;
    private Clock mutableClock;

    @BeforeEach
    void setUp() {
        leaseRepository.deleteAll();
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
    void enforcesMutualExclusionRenewalExpiryFailoverAndSafeRelease() {
        DbLeaseLockService replicaA = new DbLeaseLockService(
                leaseRepository, transactionManager, "replica-a", mutableClock);
        DbLeaseLockService replicaB = new DbLeaseLockService(
                leaseRepository, transactionManager, "replica-b", mutableClock);

        // 1. 副本 A 首次抢锁成功，副本 B 抢锁失败
        assertThat(replicaA.tryAcquire("scheduler:monitor", Duration.ofSeconds(5))).isTrue();
        assertThat(replicaB.tryAcquire("scheduler:monitor", Duration.ofSeconds(5))).isFalse();

        // 2. 同一副本 A 可重入续租，副本 B 仍无法抢占
        currentInstant.set(currentInstant.get().plusSeconds(3));
        assertThat(replicaA.tryAcquire("scheduler:monitor", Duration.ofSeconds(5))).isTrue();
        assertThat(replicaB.tryAcquire("scheduler:monitor", Duration.ofSeconds(5))).isFalse();

        // 3. 副本 B 调用 release 无法误删副本 A 持有的锁
        replicaB.release("scheduler:monitor");
        assertThat(replicaB.tryAcquire("scheduler:monitor", Duration.ofSeconds(5))).isFalse();

        // 4. 模拟副本 A 宕机不再续租，租约到期后副本 B 自动接管
        currentInstant.set(currentInstant.get().plusSeconds(6));
        assertThat(replicaB.tryAcquire("scheduler:monitor", Duration.ofSeconds(5))).isTrue();
        assertThat(replicaA.tryAcquire("scheduler:monitor", Duration.ofSeconds(5))).isFalse();

        // 5. 副本 B 主动释放后，副本 A 立即能重新获取
        replicaB.release("scheduler:monitor");
        assertThat(replicaA.tryAcquire("scheduler:monitor", Duration.ofSeconds(5))).isTrue();
    }
}
