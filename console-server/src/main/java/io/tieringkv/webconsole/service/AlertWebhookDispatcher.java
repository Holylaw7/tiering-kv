package io.tieringkv.webconsole.service;

import tools.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.AlertChannel;
import io.tieringkv.webconsole.domain.AlertEvent;
import io.tieringkv.webconsole.domain.AlertStatus;
import io.tieringkv.webconsole.repository.AlertChannelRepository;
import io.tieringkv.webconsole.security.InstanceCredentialCipher;
import io.tieringkv.webconsole.security.OutboundUrlGuard;
import io.tieringkv.webconsole.security.PinnedWebhookHttpClient;
import io.tieringkv.webconsole.web.dto.WebhookTestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * O3-B + Wave 2A H2：告警 Webhook 异步派发器。
 *
 * <p>具备三项可靠性边界：
 * <ul>
 *   <li>独立有界线程池异步发送，3 s 连接/读写超时 + 最多 2 次退避重试，绝不阻塞指标采集；</li>
 *   <li>配置签名密钥时自动附带 {@code X-TieringKV-Signature: sha256=...} HMAC-SHA256 签名；</li>
 *   <li>通过 {@link AlertStateStore} 基于 MySQL 原子条件更新/主键占位实施跨副本静默窗口与防重发控制，
 *       状态转为 RESOLVED 时清除静默并保证同一恢复事件仅被单个副本推送一次。</li>
 * </ul>
 */
@Service
public class AlertWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookDispatcher.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final long[] RETRY_BACKOFF_MS = {200L, 500L};

    private final AlertChannelRepository channelRepository;
    private final InstanceCredentialCipher cipher;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final OutboundUrlGuard urlGuard;
    private final PinnedWebhookHttpClient httpClient;
    private final AlertStateStore stateStore;

    public AlertWebhookDispatcher(AlertChannelRepository channelRepository,
                                  InstanceCredentialCipher cipher,
                                  ObjectMapper objectMapper,
                                  @Qualifier("alertWebhookExecutor") Executor executor) {
        this(channelRepository, cipher, objectMapper, executor,
                OutboundUrlGuard.permissive(), new PinnedWebhookHttpClient(), AlertStateStore.inMemoryOnly(null));
    }

    public AlertWebhookDispatcher(AlertChannelRepository channelRepository,
                                  InstanceCredentialCipher cipher,
                                  ObjectMapper objectMapper,
                                  @Qualifier("alertWebhookExecutor") Executor executor,
                                  OutboundUrlGuard urlGuard) {
        this(channelRepository, cipher, objectMapper, executor, urlGuard,
                new PinnedWebhookHttpClient(), AlertStateStore.inMemoryOnly(null));
    }

    public AlertWebhookDispatcher(AlertChannelRepository channelRepository,
                                  InstanceCredentialCipher cipher,
                                  ObjectMapper objectMapper,
                                  Executor executor,
                                  OutboundUrlGuard urlGuard,
                                  PinnedWebhookHttpClient httpClient) {
        this(channelRepository, cipher, objectMapper, executor, urlGuard,
                httpClient, AlertStateStore.inMemoryOnly(null));
    }

    @Autowired
    public AlertWebhookDispatcher(AlertChannelRepository channelRepository,
                                  InstanceCredentialCipher cipher,
                                  ObjectMapper objectMapper,
                                  @Qualifier("alertWebhookExecutor") Executor executor,
                                  OutboundUrlGuard urlGuard,
                                  AlertStateStore stateStore) {
        this(channelRepository, cipher, objectMapper, executor, urlGuard,
                new PinnedWebhookHttpClient(), stateStore);
    }

    public AlertWebhookDispatcher(AlertChannelRepository channelRepository,
                                  InstanceCredentialCipher cipher,
                                  ObjectMapper objectMapper,
                                  Executor executor,
                                  OutboundUrlGuard urlGuard,
                                  PinnedWebhookHttpClient httpClient,
                                  AlertStateStore stateStore) {
        this.channelRepository = channelRepository;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.urlGuard = urlGuard == null ? OutboundUrlGuard.strict() : urlGuard;
        this.httpClient = httpClient == null ? new PinnedWebhookHttpClient() : httpClient;
        this.stateStore = stateStore == null ? AlertStateStore.inMemoryOnly(null) : stateStore;
    }

    /** 异步派发告警触发/恢复事件至所有启用中的 Webhook 渠道。 */
    public void dispatchAsync(AlertEvent event) {
        if (event == null) {
            return;
        }
        List<AlertChannel> channels = channelRepository.findByEnabledTrueOrderByIdAsc();
        if (channels.isEmpty()) {
            return;
        }
        for (AlertChannel channel : channels) {
            if (!shouldDeliver(channel, event)) {
                continue;
            }
            executor.execute(() -> deliverSafely(channel, buildEventPayload(event)));
        }
    }

    /** 同步发送测试载荷，便于运维页面一键验证连通性与签名。 */
    public WebhookTestResult sendTest(AlertChannel channel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "TEST");
        payload.put("channel", channel.getName());
        payload.put("status", "TEST");
        payload.put("message", "Tiering-KV Console Webhook 连通性测试消息");
        payload.put("timestamp", Instant.now().toString());
        return deliverOnceWithRetry(channel, payload);
    }

    boolean shouldDeliver(AlertChannel channel, AlertEvent event) {
        boolean resolved = event.getStatus() == AlertStatus.RESOLVED;
        return stateStore.tryClaimWebhookDelivery(
                channel.getId(),
                event.getRuleId(),
                event.getInstanceId(),
                event.getId(),
                channel.getSilenceSeconds(),
                resolved);
    }

    private void deliverSafely(AlertChannel channel, Map<String, Object> payload) {
        WebhookTestResult result = deliverOnceWithRetry(channel, payload);
        if (!result.delivered()) {
            log.warn("告警 Webhook 推送失败 channel={} url={} status={} msg={}",
                    channel.getName(), channel.getUrl(), result.statusCode(), result.message());
        }
    }

    private WebhookTestResult deliverOnceWithRetry(AlertChannel channel, Map<String, Object> payload) {
        long start = System.currentTimeMillis();
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return new WebhookTestResult(false, 0, "序列化载荷失败：" + ex.getMessage(),
                    System.currentTimeMillis() - start);
        }
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = computeSignature(cipher.decrypt(channel.getSecret()), body);
        Map<String, String> extraHeaders = signature == null
                ? Map.of()
                : Map.of("X-TieringKV-Signature", "sha256=" + signature);

        int lastStatus = 0;
        String lastMessage = "未知错误";
        for (int attempt = 0; attempt <= RETRY_BACKOFF_MS.length; attempt++) {
            OutboundUrlGuard.VerifiedTarget target;
            try {
                // 每次尝试单次解析并固定目标 IP，由 PinnedWebhookHttpClient 直连该 IP（零二次 DNS 查询）
                target = urlGuard.resolveForDelivery(channel.getUrl());
            } catch (BusinessException ex) {
                return new WebhookTestResult(false, 0, ex.getMessage(),
                        System.currentTimeMillis() - start);
            }
            try {
                lastStatus = httpClient.postJson(target, bodyBytes, extraHeaders, TIMEOUT);
                if (lastStatus >= 200 && lastStatus < 300) {
                    return new WebhookTestResult(true, lastStatus, "HTTP " + lastStatus,
                            System.currentTimeMillis() - start);
                }
                if (lastStatus >= 300 && lastStatus < 400) {
                    lastMessage = "对端返回 HTTP " + lastStatus + "（重定向已禁止）";
                    break;
                }
                lastMessage = "对端返回 HTTP " + lastStatus;
                // 仅对 5xx 服务端异常重试；4xx 立即返回
                if (lastStatus < 500) {
                    break;
                }
            } catch (Exception ex) {
                lastStatus = 0;
                // 收敛网络错误文案，避免暴露底层主机或端口探测细节
                lastMessage = "网络投递失败（" + ex.getClass().getSimpleName() + "）";
            }
            if (attempt < RETRY_BACKOFF_MS.length) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS[attempt]);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new WebhookTestResult(false, lastStatus, lastMessage, System.currentTimeMillis() - start);
    }

    private Map<String, Object> buildEventPayload(AlertEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventType", "ALERT_" + event.getStatus().name());
        map.put("eventId", event.getId());
        map.put("ruleId", event.getRuleId());
        map.put("ruleName", event.getRuleName());
        map.put("instanceId", event.getInstanceId());
        map.put("metric", event.getMetric());
        map.put("value", event.getValue());
        map.put("threshold", event.getThreshold());
        map.put("level", event.getLevel() == null ? "WARN" : event.getLevel().name());
        map.put("status", event.getStatus().name());
        map.put("message", event.getMessage());
        map.put("firedAt", event.getFiredAt() == null ? null : event.getFiredAt().toString());
        map.put("resolvedAt", event.getResolvedAt() == null ? null : event.getResolvedAt().toString());
        return map;
    }

    public static String computeSignature(String secret, String body) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 不可用", ex);
        }
    }
}
