package io.tieringkv.webconsole.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** O3-A：来源 IP 防撞库限流与受信任代理 X-Forwarded-For 解析安全测试。 */
class LoginAttemptGuardIpTest {

    @Test
    void blocksIpAfterMaxFailuresAcrossDistinctUsernames() {
        LoginAttemptGuard guard = new LoginAttemptGuard(
                new LoginAttemptGuard.InMemoryLoginAttemptStore(1000), 6);

        String attackerIp = "203.0.113.42";
        for (int i = 0; i < 6; i++) {
            assertThat(guard.blockedIp(attackerIp)).isFalse();
            guard.recordFailure("user-" + i);
            guard.recordIpFailure(attackerIp);
        }

        // 单用户名均仅失败 1 次未触发账号锁定，但同源 IP 已触发防撞库限流
        assertThat(guard.blocked("user-0")).isFalse();
        assertThat(guard.blockedIp(attackerIp)).isTrue();
        assertThat(guard.remainingIpSeconds(attackerIp)).isGreaterThan(0);

        // 其他无辜 IP 不受影响
        assertThat(guard.blockedIp("198.51.100.7")).isFalse();
    }

    @Test
    void ignoresSpoofedXffFromUntrustedPeerAndResolvesViaTrustedCidr() {
        List<String> trusted = List.of("127.0.0.1", "172.16.0.0/12");

        // 1. 公网直连伪造 X-Forwarded-For 必须被忽略，仍返回真实 remoteAddr
        MockHttpServletRequest spoofed = new MockHttpServletRequest();
        spoofed.setRemoteAddr("203.0.113.99");
        spoofed.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(ClientIpResolver.resolve(spoofed, trusted)).isEqualTo("203.0.113.99");

        // 2. 来自受信任 Docker 内网代理（172.18.0.5）时，取最右侧非代理客户端 IP
        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.setRemoteAddr("172.18.0.5");
        proxied.addHeader("X-Forwarded-For", "9.9.9.9, 198.51.100.25, 172.18.0.2");
        assertThat(ClientIpResolver.resolve(proxied, trusted)).isEqualTo("198.51.100.25");
    }
}
