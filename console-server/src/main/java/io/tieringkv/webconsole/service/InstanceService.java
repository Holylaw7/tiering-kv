package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.InstanceDeletedEvent;
import io.tieringkv.webconsole.domain.InstanceStatus;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.resp.RespClient;
import io.tieringkv.webconsole.security.InstanceCredentialCipher;
import io.tieringkv.webconsole.support.InfoParser;
import io.tieringkv.webconsole.web.dto.InstanceRequest;
import io.tieringkv.webconsole.web.dto.InstanceView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 实例管理：注册、探测、维护连接池。 */
@Service
public class InstanceService {

    private final DbInstanceRepository repository;
    private final RespGateway gateway;
    private final AuditService auditService;
    private final InstanceCredentialCipher cipher;
    private ConsoleMetricsBinder metricsBinder;
    private ApplicationEventPublisher eventPublisher;

    public InstanceService(DbInstanceRepository repository, RespGateway gateway,
                           AuditService auditService) {
        this(repository, gateway, auditService, new InstanceCredentialCipher((String) null));
    }

    @Autowired
    public InstanceService(DbInstanceRepository repository, RespGateway gateway,
                           AuditService auditService, InstanceCredentialCipher cipher) {
        this.repository = repository;
        this.gateway = gateway;
        this.auditService = auditService;
        this.cipher = cipher;
    }

    @Autowired(required = false)
    void setMetricsBinder(ConsoleMetricsBinder metricsBinder) {
        this.metricsBinder = metricsBinder;
    }

    @Autowired(required = false)
    void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public List<InstanceView> list() {
        return repository.findAllByOrderByIdAsc().stream().map(InstanceView::of).toList();
    }

    public List<DbInstance> enabled() {
        return repository.findByEnabledTrueOrderByIdAsc();
    }

    public DbInstance require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("实例不存在：" + id));
    }

    @Transactional
    public InstanceView create(InstanceRequest request) {
        String host = request.host().trim();
        if (repository.existsByHostAndPort(host, request.port())) {
            throw BusinessException.conflict("该地址已纳管：" + host + ":" + request.port());
        }
        repository.findByName(request.name().trim()).ifPresent(existing -> {
            throw BusinessException.conflict("实例名称已存在：" + request.name());
        });
        DbInstance instance = new DbInstance();
        instance.setName(request.name().trim());
        instance.setHost(host);
        instance.setPort(request.port());
        instance.setPassword(cipher.encrypt(request.password()));
        instance.setRemark(request.remark());
        instance.setEnabled(request.enabled() == null || request.enabled());
        applyTls(instance, request, true);
        repository.save(instance);
        auditService.record("INSTANCE_CREATE", instance.getName(),
                host + ":" + instance.getPort() + (instance.isTlsEnabled() ? " (TLS)" : ""), true);
        return InstanceView.of(instance);
    }

    @Transactional
    public InstanceView update(Long id, InstanceRequest request) {
        DbInstance instance = require(id);
        String name = request.name().trim();
        String host = request.host().trim();
        repository.findByName(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw BusinessException.conflict("实例名称已存在：" + name);
                });
        if (!host.equals(instance.getHost()) || request.port() != instance.getPort()) {
            if (repository.existsByHostAndPort(host, request.port())) {
                throw BusinessException.conflict("该地址已纳管：" + host + ":" + request.port());
            }
        }
        instance.setName(name);
        instance.setHost(host);
        instance.setPort(request.port());
        if (request.password() != null && !request.password().isBlank()) {
            instance.setPassword(cipher.encrypt(request.password()));
        }
        instance.setRemark(request.remark());
        if (request.enabled() != null) {
            instance.setEnabled(request.enabled());
        }
        applyTls(instance, request, false);
        repository.save(instance);
        gateway.evict(id);
        auditService.record("INSTANCE_UPDATE", instance.getName(),
                instance.isTlsEnabled() ? "tls=true" : null, true);
        return InstanceView.of(instance);
    }

    private void applyTls(DbInstance instance, InstanceRequest request, boolean creating) {
        if (request.tlsEnabled() != null) {
            instance.setTlsEnabled(request.tlsEnabled());
        } else if (creating) {
            instance.setTlsEnabled(false);
        }
        if (request.tlsVerifyHostname() != null) {
            instance.setTlsVerifyHostname(request.tlsVerifyHostname());
        } else if (creating) {
            instance.setTlsVerifyHostname(true);
        }
        if (request.tlsCaCert() != null) {
            String trimmed = request.tlsCaCert().trim();
            if (!trimmed.isEmpty()) {
                try {
                    RespClient.validateCaCertPem(trimmed);
                } catch (IllegalArgumentException ex) {
                    throw BusinessException.badRequest(ex.getMessage());
                }
                instance.setTlsCaCert(trimmed);
            } else if (creating) {
                instance.setTlsCaCert(null);
            }
        }
        if (!instance.isTlsEnabled()) {
            // 关闭 TLS 时保留或不影响连接行为；不做强制清空以免误删历史 CA
        }
    }

    @Transactional
    public void delete(Long id) {
        DbInstance instance = require(id);
        repository.delete(instance);
        gateway.evict(id);
        if (metricsBinder != null) {
            metricsBinder.unregisterInstance(id);
        }
        if (eventPublisher != null) {
            ApplicationEventPublisher publisher = eventPublisher;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publisher.publishEvent(new InstanceDeletedEvent(id));
                    }
                });
            } else {
                publisher.publishEvent(new InstanceDeletedEvent(id));
            }
        }
        auditService.record("INSTANCE_DELETE", instance.getName(), null, true);
    }

    /** 探测：连通性 + 版本，落库状态。 */
    @Transactional
    public InstanceView probe(Long id) {
        DbInstance instance = require(id);
        return InstanceView.of(probe(instance));
    }

    @Transactional
    public DbInstance probe(DbInstance instance) {
        try {
            String info = gateway.info(instance);
            Map<String, Map<String, String>> sections = InfoParser.parse(info);
            String version = InfoParser.get(sections, "Server", "redis_version");
            instance.setVersion(version == null
                    ? InfoParser.get(sections, "Server", "tieringkv_version") : version);
            instance.setStatus(InstanceStatus.UP);
        } catch (RuntimeException ex) {
            instance.setStatus(InstanceStatus.DOWN);
            instance.setVersion(null);
            gateway.evict(instance.getId());
        }
        instance.setLastProbeAt(LocalDateTime.now());
        return repository.save(instance);
    }
}
