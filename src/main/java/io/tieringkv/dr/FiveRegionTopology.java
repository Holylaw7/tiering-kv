package io.tieringkv.dr;

import java.util.LinkedHashMap;
import java.util.Map;

/** 三地五中心拓扑（ADR-0123）：2 主 + 2 备 + 1 仲裁。 */
public final class FiveRegionTopology {

    private FiveRegionTopology() {
    }

    public static DrTopology of(String primaryA, String primaryB,
                                String backupA, String backupB,
                                String arbiter) {
        Map<String, DrRole> roles = new LinkedHashMap<>();
        roles.put(primaryA, DrRole.PRIMARY);
        roles.put(primaryB, DrRole.PRIMARY);
        roles.put(backupA, DrRole.SECONDARY);
        roles.put(backupB, DrRole.SECONDARY);
        roles.put(arbiter, DrRole.OBSERVER);
        return new DrTopology(roles, Map.of());
    }
}
