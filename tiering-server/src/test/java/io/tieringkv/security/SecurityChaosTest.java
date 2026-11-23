package io.tieringkv.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 安全混沌（ADR-0106）：RBAC 矩阵、令牌生命周期、吊销/轮换/过期。 */
class SecurityChaosTest {

    @Test
    void issueAndValidate() {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.ADMIN, 60_000);
        assertThat(manager.validate(token)).isEqualTo(Role.ADMIN);
    }

    @ParameterizedTest(name = "role {0}")
    @EnumSource(Role.class)
    void roleRoundTrip(Role role) {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(role, 60_000);
        assertThat(manager.validate(token)).isEqualTo(role);
    }

    @ParameterizedTest(name = "role {0} permission {1}")
    @EnumSource(Role.class)
    void rbacPermissionMatrix(Role role) {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(role, 60_000);
        for (Permission permission : Permission.values()) {
            if (role.allows(permission)) {
                assertThat(manager.allows(token, permission)).isTrue();
                manager.require(token, permission);
            } else {
                assertThat(manager.allows(token, permission)).isFalse();
                assertThatThrownBy(() -> manager.require(token, permission))
                        .isInstanceOf(SecurityException.class);
            }
        }
    }

    @Test
    void unknownTokenRejected() {
        CredentialManager manager = new CredentialManager();
        assertThatThrownBy(() -> manager.validate("nope"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void blankTokenRejected() {
        CredentialManager manager = new CredentialManager();
        assertThatThrownBy(() -> manager.validate(""))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> manager.validate(null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void expiredTokenRejected() {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.READER, 1_000);
        assertThat(manager.validateAt(token,
                System.currentTimeMillis() + 500))
                .isEqualTo(Role.READER); // 2s 后未过期
        assertThatThrownBy(() -> manager.validateAt(token,
                System.currentTimeMillis() + 60_000))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void revokedTokenRejected() {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.WRITER, 60_000);
        manager.revoke(token);
        assertThatThrownBy(() -> manager.validate(token))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rotationInvalidatesOldToken() {
        CredentialManager manager = new CredentialManager();
        String old = manager.issue(Role.ADMIN, 60_000);
        String fresh = manager.rotate(old, 60_000);
        assertThat(fresh).isNotEqualTo(old);
        assertThat(manager.validate(fresh)).isEqualTo(Role.ADMIN);
        assertThatThrownBy(() -> manager.validate(old))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void readerCannotWrite() {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.READER, 60_000);
        assertThat(manager.allows(token, Permission.READ)).isTrue();
        assertThat(manager.allows(token, Permission.WRITE)).isFalse();
        assertThatThrownBy(() -> manager.require(token, Permission.WRITE))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void writerCannotBackupOrCdc() {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.WRITER, 60_000);
        assertThat(manager.allows(token, Permission.BACKUP)).isFalse();
        assertThatThrownBy(() -> manager.require(token, Permission.BACKUP))
                .isInstanceOf(SecurityException.class);
        assertThat(manager.allows(token, Permission.CDC)).isFalse();
        assertThatThrownBy(() -> manager.require(token, Permission.CDC))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void backupOperatorCannotWrite() {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.BACKUP_OPERATOR, 60_000);
        assertThat(manager.allows(token, Permission.BACKUP)).isTrue();
        assertThat(manager.allows(token, Permission.WRITE)).isFalse();
        assertThatThrownBy(() -> manager.require(token, Permission.WRITE))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void cdcConsumerCannotWrite() {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.CDC_CONSUMER, 60_000);
        assertThat(manager.allows(token, Permission.CDC)).isTrue();
        assertThat(manager.allows(token, Permission.WRITE)).isFalse();
        assertThatThrownBy(() -> manager.require(token, Permission.WRITE))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void concurrentValidationSafe() throws Exception {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.ADMIN, 60_000);
        int threads = 8;
        int perThread = 200;
        AtomicInteger failures = new AtomicInteger();
        List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    try {
                        assertThat(manager.validate(token))
                                .isEqualTo(Role.ADMIN);
                    } catch (AssertionError e) {
                        failures.incrementAndGet();
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(10_000);
        }
        assertThat(failures.get()).isZero();
    }

    @ParameterizedTest(name = "tokens {0}")
    @ValueSource(ints = {1, 10, 100, 500})
    void parameterizedTokenVolume(int count) {
        CredentialManager manager = new CredentialManager();
        for (int i = 0; i < count; i++) {
            manager.issue(Role.WRITER, 60_000);
        }
        assertThat(manager.activeTokenCount()).isEqualTo(count);
    }

    @Test
    void expiredTokensPurgedFromCount() throws Exception {
        CredentialManager manager = new CredentialManager();
        manager.issue(Role.READER, 1);
        Thread.sleep(5);
        manager.issue(Role.READER, 60_000);
        assertThat(manager.activeTokenCount()).isEqualTo(1);
    }

    @Test
    void issueGeneratesUniqueTokens() {
        CredentialManager manager = new CredentialManager();
        String first = manager.issue(Role.ADMIN, 60_000);
        String second = manager.issue(Role.ADMIN, 60_000);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rolePermissionSetsFrozen() {
        assertThat(Role.READER.permissions()).containsExactly(
                Permission.READ);
        assertThat(Role.WRITER.permissions()).containsExactlyInAnyOrder(
                Permission.READ, Permission.WRITE);
        assertThat(Role.ADMIN.permissions())
                .containsExactlyInAnyOrder(Permission.values());
        assertThat(Role.BACKUP_OPERATOR.permissions())
                .containsExactlyInAnyOrder(Permission.READ,
                        Permission.BACKUP);
        assertThat(Role.CDC_CONSUMER.permissions())
                .containsExactlyInAnyOrder(Permission.READ,
                        Permission.CDC);
    }
}
