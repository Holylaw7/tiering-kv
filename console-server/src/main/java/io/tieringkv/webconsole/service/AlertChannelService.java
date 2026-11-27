package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.AlertChannel;
import io.tieringkv.webconsole.repository.AlertChannelRepository;
import io.tieringkv.webconsole.security.InstanceCredentialCipher;
import io.tieringkv.webconsole.security.OutboundUrlGuard;
import io.tieringkv.webconsole.web.dto.AlertChannelRequest;
import io.tieringkv.webconsole.web.dto.AlertChannelView;
import io.tieringkv.webconsole.web.dto.WebhookTestResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Locale;

/** O3-B：告警 Webhook 通知渠道管理服务。 */
@Service
public class AlertChannelService {

    private final AlertChannelRepository repository;
    private final InstanceCredentialCipher cipher;
    private final AlertWebhookDispatcher dispatcher;
    private final AuditService auditService;
    private final OutboundUrlGuard urlGuard;
    private AlertStateStore stateStore;

    public AlertChannelService(AlertChannelRepository repository,
                               InstanceCredentialCipher cipher,
                               AlertWebhookDispatcher dispatcher,
                               AuditService auditService) {
        this(repository, cipher, dispatcher, auditService, OutboundUrlGuard.strict());
    }

    @Autowired
    public AlertChannelService(AlertChannelRepository repository,
                               InstanceCredentialCipher cipher,
                               AlertWebhookDispatcher dispatcher,
                               AuditService auditService,
                               OutboundUrlGuard urlGuard) {
        this.repository = repository;
        this.cipher = cipher;
        this.dispatcher = dispatcher;
        this.auditService = auditService;
        this.urlGuard = urlGuard == null ? OutboundUrlGuard.strict() : urlGuard;
    }

    @Autowired(required = false)
    void setStateStore(AlertStateStore stateStore) {
        this.stateStore = stateStore;
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
        String url = urlGuard.validateForRegistration(request.url());
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
        channel.setUrl(urlGuard.validateForRegistration(request.url()));
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
        if (stateStore != null) {
            AlertStateStore store = stateStore;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        store.clearForChannel(id);
                    }
                });
            } else {
                store.clearForChannel(id);
            }
        }
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
}
