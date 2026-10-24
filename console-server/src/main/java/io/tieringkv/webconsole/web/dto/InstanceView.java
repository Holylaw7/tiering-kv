package io.tieringkv.webconsole.web.dto;

import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.InstanceStatus;

import java.time.LocalDateTime;

/** 实例视图（不返回口令）。 */
public record InstanceView(Long id, String name, String host, int port,
                           InstanceStatus status, String version,
                           LocalDateTime lastProbeAt, String remark,
                           boolean enabled, boolean hasPassword) {

    public static InstanceView of(DbInstance instance) {
        return new InstanceView(instance.getId(), instance.getName(), instance.getHost(),
                instance.getPort(), instance.getStatus(), instance.getVersion(),
                instance.getLastProbeAt(), instance.getRemark(), instance.isEnabled(),
                instance.getPassword() != null && !instance.getPassword().isBlank());
    }
}
