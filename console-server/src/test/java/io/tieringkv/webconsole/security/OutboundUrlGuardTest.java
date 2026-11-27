package io.tieringkv.webconsole.security;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.AlertChannel;
import io.tieringkv.webconsole.repository.AlertChannelRepository;
import io.tieringkv.webconsole.service.AlertWebhookDispatcher;
import io.tieringkv.webconsole.web.dto.WebhookTestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Wave 1 S1：Webhook 出站 URL SSRF、双栈 A/AAAA、尾部点号、IPv6 隧道、DNS 超时与空结果 fail-closed 验证。 */
class OutboundUrlGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/actuator/prometheus",
            "http://localhost:6379/",
            "http://localhost./hook",
            "http://sub.localhost/hook",
            "http://sub.localhost./hook",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.100.100.200/latest/meta-data/",
            "http://10.0.0.12:3306/",
            "http://172.16.5.9/hook",
            "http://192.168.1.100/hook",
            "http://0.0.0.0:8080/",
            "http://[::1]:8080/hook",
            "http://[fe80::1]/hook",
            "http://[fc00::1]/hook",
            "http://[::127.0.0.1]/hook",
            "http://[::ffff:127.0.0.1]/hook",
            "http://[::ffff:169.254.169.254]/latest/meta-data/",
            "http://[64:ff9b::169.254.169.254]/latest/meta-data/",
            "http://[2002:7f00:0001::]/hook",
            "http://[2001::1]/hook",
            "http://metadata.google.internal/computeMetadata/v1/",
            "http://metadata.google.internal./computeMetadata/v1/",
            "http://user:pass@hooks.example.com/alerts",
            "ftp://hooks.example.com/alerts",
            "file:///etc/passwd"
    })
    void blocksLoopbackPrivateMetadataIpv6TunnelsAndTrailingDotVariants(String unsafeUrl) {
        OutboundUrlGuard guard = OutboundUrlGuard.strict();
        assertThatThrownBy(() -> guard.validateForRegistration(unsafeUrl))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(400);
    }

    @Test
    void rejectsDualStackWhenAnyAOrAaaaRecordIsBlocked() throws Exception {
        OutboundUrlGuard.HostResolver dualStackWithLoopback = host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("::1")
        };
        OutboundUrlGuard guard1 = new OutboundUrlGuard(true, List.of(), dualStackWithLoopback);
        assertThatThrownBy(() -> guard1.resolveForDelivery("https://dual.example.org/hook"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("安全策略拦截");

        OutboundUrlGuard.HostResolver dualStackWithNat64 = host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("64:ff9b::169.254.169.254")
        };
        OutboundUrlGuard guard2 = new OutboundUrlGuard(true, List.of(), dualStackWithNat64);
        assertThatThrownBy(() -> guard2.resolveForDelivery("https://dual-nat64.example.org/hook"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("安全策略拦截");
    }

    @Test
    void failsClosedOnDnsTimeoutAndEmptyResults() {
        OutboundUrlGuard.HostResolver slowDns = host -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        };
        OutboundUrlGuard timeoutGuard = new OutboundUrlGuard(true, List.of(), slowDns, 100L);
        assertThatThrownBy(() -> timeoutGuard.resolveForDelivery("https://slow.example.org/hook"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超时");

        OutboundUrlGuard emptyGuard = new OutboundUrlGuard(true, List.of(), host -> new InetAddress[0]);
        assertThatThrownBy(() -> emptyGuard.resolveForDelivery("https://empty.example.org/hook"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未返回有效地址");
    }

    @Test
    void allowsPublicAddressesAndBlocksDnsRebindingAtDeliveryTime() throws Exception {
        AtomicBoolean rebound = new AtomicBoolean(false);
        OutboundUrlGuard.HostResolver dynamicDns = host -> {
            if ("rebind.example.org".equals(host)) {
                return rebound.get()
                        ? new InetAddress[]{InetAddress.getByName("169.254.169.254")}
                        : new InetAddress[]{InetAddress.getByName("93.184.216.34")};
            }
            throw new UnknownHostException(host);
        };
        OutboundUrlGuard guard = new OutboundUrlGuard(true, List.of(), dynamicDns);

        assertThat(guard.validateForRegistration("https://rebind.example.org/hook"))
                .isEqualTo("https://rebind.example.org/hook");
        OutboundUrlGuard.VerifiedTarget verified = guard.resolveForDelivery("https://rebind.example.org/hook");
        assertThat(verified.pinnedAddress().getHostAddress()).isEqualTo("93.184.216.34");
        assertThat(verified.originalHost()).isEqualTo("rebind.example.org");

        // 发包期发生 DNS Rebinding 指向云元数据 IP：必须拦截
        rebound.set(true);
        assertThatThrownBy(() -> guard.resolveForDelivery("https://rebind.example.org/hook"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("安全策略拦截");
    }

    @Test
    void toleratesOfflineUnresolvablePublicHostAtRegistrationButFailsClosedOnDelivery() {
        OutboundUrlGuard.HostResolver offlineDns = host -> {
            throw new UnknownHostException(host);
        };
        OutboundUrlGuard guard = new OutboundUrlGuard(true, List.of(), offlineDns);

        assertThat(guard.validateForRegistration("https://hooks.example.com/alerts"))
                .isEqualTo("https://hooks.example.com/alerts");

        assertThatThrownBy(() -> guard.resolveForDelivery("https://hooks.example.com/alerts"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法解析");
    }

    @Test
    void respectsPrivateAllowlistButNeverLetsHostnameAllowlistReachCloudMetadata() throws Exception {
        OutboundUrlGuard.HostResolver corpDns = host -> switch (host) {
            case "feishu-gw.corp" -> new InetAddress[]{InetAddress.getByName("10.20.8.15")};
            case "poisoned-gw.corp" -> new InetAddress[]{InetAddress.getByName("169.254.169.254")};
            case "poisoned-ali.corp" -> new InetAddress[]{InetAddress.getByName("100.100.100.200")};
            case "other-db.corp" -> new InetAddress[]{InetAddress.getByName("10.21.8.15")};
            default -> InetAddress.getAllByName(host);
        };
        OutboundUrlGuard guard = new OutboundUrlGuard(
                true, List.of("10.20.0.0/16", "feishu-gw.corp", "poisoned-gw.corp", "poisoned-ali.corp"), corpDns);

        assertThatCode(() -> guard.validateForRegistration("http://10.20.1.5:8080/hook"))
                .doesNotThrowAnyException();
        assertThat(guard.resolveForDelivery("https://feishu-gw.corp/webhook").pinnedAddress().getHostAddress())
                .isEqualTo("10.20.8.15");

        // 即使主机名被写入白名单，一旦 DNS 投毒指向云元数据也必须拦截
        assertThatThrownBy(() -> guard.resolveForDelivery("https://poisoned-gw.corp/latest/meta-data/"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("云元数据");
        assertThatThrownBy(() -> guard.resolveForDelivery("http://poisoned-ali.corp/latest/meta-data/"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("云元数据");

        // 不在白名单的相邻内网段依然拦截
        assertThatThrownBy(() -> guard.resolveForDelivery("http://other-db.corp/hook"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void dispatcherRejectsBlockedTargetImmediatelyWithoutNetworkCall() {
        AlertChannelRepository repo = mock(AlertChannelRepository.class);
        InstanceCredentialCipher cipher = new InstanceCredentialCipher((String) null);
        AlertWebhookDispatcher dispatcher = new AlertWebhookDispatcher(
                repo, cipher, new ObjectMapper(), Runnable::run, OutboundUrlGuard.strict());

        AlertChannel channel = new AlertChannel();
        channel.setId(99L);
        channel.setName("ssrf-probe");
        channel.setType("WEBHOOK");
        channel.setUrl("http://169.254.169.254/latest/meta-data/");
        channel.setEnabled(true);

        WebhookTestResult result = dispatcher.sendTest(channel);
        assertThat(result.delivered()).isFalse();
        assertThat(result.statusCode()).isZero();
        assertThat(result.message()).contains("安全策略拦截");
    }
}
