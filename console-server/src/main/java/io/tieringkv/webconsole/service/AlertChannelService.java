package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.AlertChannel;
import io.tieringkv.webconsole.repository.AlertChannelRepository;
import io.tieringkv.webconsole.security.InstanceCredentialCipher;
import io.tieringkv.webconsole.web.dto.AlertChannelRequest;
import io.tieringkv.webconsole.web.dto.AlertChannelView;
import io.tieringkv.webconsole.web.dto.WebhookTestResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** O3-B：告警 Webhook 通知渠道管理服务。 */
@Service
public class AlertChannelService {

    private final AlertChannelRepository repository;
    private final InstanceCredentialCipher cipher;
    private final AlertWebhookDispatcher dispatcher;
    private final AuditService auditService;

    public AlertChannelService(AlertChannelRepository repository,
                               InstanceCredentialCipher cipher,
                               AlertWebhookDispatcher dispatcher,
                               AuditService auditService) {
        this.repository = repository;
        this.cipher = cipher;
        this.dispatcher = dispatcher;
        this.auditService = auditService;
    }

    public List<AlertChannelView> list() {
        return repository.findAllByOrderByIdAsc().stream().map(AlertChannelView::of).toList();
    }

    @Transactional
    public AlertChannelView create(AlertChannelRequest request) {
        String name = request.name().trim();
        if (repository.findByName(name).isPresent()) {
            throw BusinessException.conflict("通知渠道名称已存在：" + name);
        }
        String url = validateUrl(request.url());
        AlertChannel channel = new AlertChannel();
        channel.setName(name);
        channel.setType(normalizeType(request.type()));
        channel.setUrl(url);
        if (request.secret() != null && !request.secret().isBlank()) {
            channel.setSecret(cipher.encrypt(request.secret().trim()));
        }
        channel.setSilenceSeconds(request.silenceSeconds() == null ? 300 : request.silenceSeconds());
        channel.setEnabled(request.enabled() == null || request.enabled());
        repository.save(channel);
        auditService.record("ALERT_CHANNEL_CREATE", channel.getName(), channel.getUrl(), true);
        return AlertChannelView.of(channel);
    }

    @Transactional
    public AlertChannelView update(Long id, AlertChannelRequest request) {
        AlertChannel channel = repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("通知渠道不存在：" + id));
        String name = request.name().trim();
        repository.findByName(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw BusinessException.conflict("通知渠道名称已存在：" + name);
                });
        channel.setName(name);
        channel.setType(normalizeType(request.type()));
        channel.setUrl(validateUrl(request.url()));
        if (request.secret() != null && !request.secret().isBlank()) {
            channel.setSecret(cipher.encrypt(request.secret().trim()));
        }
        if (request.silenceSeconds() != null) {
            channel.setSilenceSeconds(request.silenceSeconds());
        }
        if (request.enabled() != null) {
            channel.setEnabled(request.enabled());
        }
        repository.save(channel);
        auditService.record("ALERT_CHANNEL_UPDATE", channel.getName(), channel.getUrl(), true);
        return AlertChannelView.of(channel);
    }

    @Transactional
    public void delete(Long id) {
        AlertChannel channel = repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("通知渠道不存在：" + id));
        repository.delete(channel);
        auditService.record("ALERT_CHANNEL_DELETE", channel.getName(), null, true);
    }

    public WebhookTestResult test(Long id) {
        AlertChannel channel = repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("通知渠道不存在：" + id));
        WebhookTestResult result = dispatcher.sendTest(channel);
        auditService.record("ALERT_CHANNEL_TEST", channel.getName(),
                "status=" + result.statusCode() + " " + result.message(), result.delivered());
        return result;
    }

    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "WEBHOOK";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (!"WEBHOOK".equals(upper)) {
            throw BusinessException.badRequest("暂仅支持 WEBHOOK 类型渠道");
        }
        return upper;
    }

    private static String validateUrl(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw BusinessException.badRequest("Webhook URL 必须为合法的 http/https 地址");
            }
            return trimmed;
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("Webhook URL 格式非法：" + ex.getMessage());
        }
    }
}
