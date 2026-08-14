package io.tieringkv.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Security 边缘矩阵（ADR-0106）：TTL、轮换并发、吊销幂等。 */
class SecurityEdgeTest {

    @ParameterizedTest(name = "ttl {0}")
    @ValueSource(longs = {1_000, 5_000, 60_000})
    void parameterizedTtl(long ttlMillis) throws Exception {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.READER, ttlMillis);
        // TTL 1ms 在签发与验证之间的真实调度延迟下必然过期，无法稳定断言"未到期"；
        // 保留短/中/长 TTL 边界，窗口取 min(ttl-1, 500ms) 使断言不受 CI 调度抖动影响。
        assertThat(manager.validateAt(token,
                System.currentTimeMillis() + Math.min(ttlMillis - 1, 500)))
                .isEqualTo(Role.READER);
        assertThatThrownBy(() -> manager.validateAt(token,
                System.currentTimeMillis() + ttlMillis + 1_000))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rotationCarriesExpiry() {
        CredentialManager manager = new CredentialManager();
        String old = manager.issue(Role.WRITER, 60_000);
        String rotated = manager.rotate(old, 10);
        assertThat(manager.validate(rotated)).isEqualTo(Role.WRITER);
        assertThatThrownBy(() -> manager.validateAt(rotated,
                System.currentTimeMillis() + 60_000))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void concurrentRotateAndValidate() throws Exception {
        CredentialManager manager = new CredentialManager();
        java.util.concurrent.atomic.AtomicReference<String> token =
                new java.util.concurrent.atomic.AtomicReference<>(
                        manager.issue(Role.ADMIN, 60_000));
        AtomicInteger failures = new AtomicInteger();
        Thread rotator = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    token.set(manager.rotate(token.get(), 60_000));
                } catch (SecurityException e) {
                    // 旧令牌被并发吊销属预期
                }
            }
        });
        Thread validator = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                try {
                    manager.validate(token.get());
                } catch (SecurityException e) {
                    // 轮换窗口内旧令牌失效属预期
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }
        });
        rotator.start();
        validator.start();
        rotator.join(10_000);
        validator.join(10_000);
        assertThat(failures.get()).isZero();
    }

    @Test
    void revokeTwiceIdempotent() {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.READER, 60_000);
        manager.revoke(token);
        manager.revoke(token);
        assertThatThrownBy(() -> manager.validate(token))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void allowsWithNullTokenRejected() {
        CredentialManager manager = new CredentialManager();
        assertThatThrownBy(() -> manager.allows(null, Permission.READ))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rolePermissionSetsStable() {
        assertThat(Role.ADMIN.allows(Permission.READ)).isTrue();
        assertThat(Role.ADMIN.allows(Permission.WRITE)).isTrue();
        assertThat(Role.ADMIN.allows(Permission.ADMIN)).isTrue();
        assertThat(Role.ADMIN.allows(Permission.BACKUP)).isTrue();
        assertThat(Role.ADMIN.allows(Permission.CDC)).isTrue();
        assertThat(Role.READER.allows(Permission.ADMIN)).isFalse();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {2, 50})
    void parameterizedIssueVolume(int count) {
        CredentialManager manager = new CredentialManager();
        for (int i = 0; i < count; i++) {
            manager.issue(Role.CDC_CONSUMER, 60_000);
        }
        assertThat(manager.activeTokenCount()).isEqualTo(count);
    }
}
