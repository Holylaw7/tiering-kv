package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.InstanceStatus;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.support.InfoParser;
import io.tieringkv.webconsole.web.dto.InstanceRequest;
import io.tieringkv.webconsole.web.dto.InstanceView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 实例管理：注册、探测、维护连接池。 */
@Service
public class InstanceService {

    private final DbInstanceRepository repository;
    private final RespGateway gateway;
    private final AuditService auditService;

    public InstanceService(DbInstanceRepository repository, RespGateway gateway,
                           AuditService auditService) {
        this.repository = repository;
        this.gateway = gateway;
        this.auditService = auditService;
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
        instance.setPassword(request.password());
        instance.setRemark(request.remark());
        instance.setEnabled(request.enabled() == null || request.enabled());
        repository.save(instance);
        auditService.record("INSTANCE_CREATE", instance.getName(),
                host + ":" + instance.getPort(), true);
        return InstanceView.of(instance);
    }

    @Transactional
    public InstanceView update(Long id, InstanceRequest request) {
        DbInstance instance = require(id);
        instance.setName(request.name().trim());
        instance.setHost(request.host().trim());
        instance.setPort(request.port());
        if (request.password() != null && !request.password().isBlank()) {
            instance.setPassword(request.password());
        }
        instance.setRemark(request.remark());
        if (request.enabled() != null) {
            instance.setEnabled(request.enabled());
        }
        repository.save(instance);
        gateway.evict(id);
        auditService.record("INSTANCE_UPDATE", instance.getName(), null, true);
        return InstanceView.of(instance);
    }

    @Transactional
    public void delete(Long id) {
        DbInstance instance = require(id);
        repository.delete(instance);
        gateway.evict(id);
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
