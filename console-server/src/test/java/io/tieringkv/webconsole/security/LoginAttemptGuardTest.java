package io.tieringkv.webconsole.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptGuardTest {

    @Test
    void blocksAfterFiveFailures() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        for (int i = 0; i < 4; i++) {
            guard.recordFailure("admin");
        }
        assertThat(guard.blocked("admin")).isFalse();
        guard.recordFailure("admin");
        assertThat(guard.blocked("admin")).isTrue();
        assertThat(guard.remainingSeconds("admin")).isPositive();
    }

    @Test
    void successClearsFailures() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        guard.recordFailure("admin");
        guard.recordFailure("admin");
        guard.recordSuccess("admin");
        assertThat(guard.blocked("admin")).isFalse();
    }

    @Test
    void usernameIsCaseInsensitiveAndTrimmed() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        guard.recordFailure("  Admin ");
        for (int i = 0; i < 4; i++) {
            guard.recordFailure("admin");
        }
        assertThat(guard.blocked("ADMIN")).isTrue();
    }

    @Test
    void unknownUserIsNotBlocked() {
        assertThat(new LoginAttemptGuard().blocked("nobody")).isFalse();
    }
}
