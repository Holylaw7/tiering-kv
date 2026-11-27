package io.tieringkv.webconsole.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.tieringkv.webconsole.domain.AlertChannel;
import io.tieringkv.webconsole.domain.AlertEvent;
import io.tieringkv.webconsole.domain.AlertLevel;
import io.tieringkv.webconsole.domain.AlertStatus;
import io.tieringkv.webconsole.repository.AlertChannelRepository;
import io.tieringkv.webconsole.security.InstanceCredentialCipher;
import io.tieringkv.webconsole.web.dto.WebhookTestResult;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** O3-B：告警 Webhook 载荷、HMAC-SHA256 签名、静默去重与 5xx 重试验证。 */
class AlertWebhookDispatcherTest {

    private record Received(String body, String signature) {
    }

    @Test
    void deliversSignedFiringRespectsSilenceAllowsResolvedAndRetries5xx() throws Exception {
        List<Received> received = new CopyOnWriteArrayList<>();
        AtomicInteger callCount = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            String sig = exchange.getRequestHeaders().getFirst("X-TieringKV-Signature");
            int n = callCount.incrementAndGet();
            if (n == 1) {
                // 首次模拟对端 503，验证自动重试恢复
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            received.add(new Received(body, sig));
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            InstanceCredentialCipher cipher = new InstanceCredentialCipher("o3-master-encryption-key-0123456");

            AlertChannel channel = new AlertChannel();
            channel.setId(7L);
            channel.setName("ops-hook");
            channel.setType("WEBHOOK");
            channel.setUrl(url);
            channel.setSecret(cipher.encrypt("hook-signing-secret"));
            channel.setSilenceSeconds(300);
            channel.setEnabled(true);

            AlertChannelRepository repo = mock(AlertChannelRepository.class);
            when(repo.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(channel));

            ObjectMapper mapper = new ObjectMapper();
            AlertWebhookDispatcher dispatcher = new AlertWebhookDispatcher(repo, cipher, mapper, Runnable::run);

            // 1. 首次触发经历 503 -> 重试 200 成功
            AlertEvent firing = sampleEvent(AlertStatus.FIRING);
            dispatcher.dispatchAsync(firing);
            assertThat(callCount.get()).isEqualTo(2);
            assertThat(received).hasSize(1);

            Received first = received.get(0);
            String expectedSig = "sha256=" + AlertWebhookDispatcher.computeSignature("hook-signing-secret", first.body());
            assertThat(first.signature()).isEqualTo(expectedSig);
            JsonNode payload = mapper.readTree(first.body());
            assertThat(payload.path("eventType").asText()).isEqualTo("ALERT_FIRING");
            assertThat(payload.path("ruleName").asText()).isEqualTo("high-latency");

            // 2. 静默窗口内再次 FIRING 必须抑制不重复发送
            dispatcher.dispatchAsync(sampleEvent(AlertStatus.FIRING));
            assertThat(received).hasSize(1);

            // 3. 恢复事件豁免静默立即推送
            AlertEvent resolved = sampleEvent(AlertStatus.RESOLVED);
            resolved.setResolvedAt(LocalDateTime.now());
            dispatcher.dispatchAsync(resolved);
            assertThat(received).hasSize(2);
            assertThat(mapper.readTree(received.get(1).body()).path("eventType").asText())
                    .isEqualTo("ALERT_RESOLVED");

            // 4. 一键测试推送
            WebhookTestResult testRes = dispatcher.sendTest(channel);
            assertThat(testRes.delivered()).isTrue();
            assertThat(testRes.statusCode()).isEqualTo(200);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redactUrlStripsCredentialsPathsAndQueryTokens() {
        assertThat(AlertWebhookDispatcher.redactUrl(
                "https://user:pass@oapi.dingtalk.com:8443/robot/send?access_token=top-secret#frag"))
                .isEqualTo("https://oapi.dingtalk.com:8443");
        assertThat(AlertWebhookDispatcher.redactUrl(
                "https://hooks.slack.com/services/T000/B000/SECRET_TOKEN"))
                .isEqualTo("https://hooks.slack.com");
        assertThat(AlertWebhookDispatcher.redactUrl(" ")).isEqualTo("<empty>");
        assertThat(AlertWebhookDispatcher.redactUrl(null)).isEqualTo("<empty>");
        assertThat(AlertWebhookDispatcher.redactUrl("not a valid uri ::")).isEqualTo("<redacted>");
        assertThat(AlertWebhookDispatcher.redactUrl("relative/path")).isEqualTo("<redacted>");
    }

    private AlertEvent sampleEvent(AlertStatus status) {
        AlertEvent ev = new AlertEvent();
        ev.setId(101L);
        ev.setRuleId(11L);
        ev.setInstanceId(1L);
        ev.setRuleName("high-latency");
        ev.setMetric("avg_latency_ms");
        ev.setValue(18.5);
        ev.setThreshold(5.0);
        ev.setLevel(AlertLevel.CRIT);
        ev.setStatus(status);
        ev.setMessage("平均延迟超阈值");
        ev.setFiredAt(LocalDateTime.now());
        return ev;
    }
}
