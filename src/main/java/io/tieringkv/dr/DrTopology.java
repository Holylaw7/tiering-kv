package io.tieringkv.dr;

import java.util.Map;

/** 容灾拓扑（ADR-0115）：地域 → 角色与复制模式。 */
public record DrTopology(Map<String, DrRole> roles,
                         Map<String, io.tieringkv.replication
                                 .ReplicationMode> modes) {

    public DrTopology {
        roles = Map.copyOf(roles);
        modes = Map.copyOf(modes);
    }

    public DrRole role(String region) {
        return roles.getOrDefault(region, DrRole.OBSERVER);
    }
}
