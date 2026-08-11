package io.tieringkv.replication.active;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** 全局多活自动选主（ADR-0143）：健康探测 + 自动切换。 */
public final class LeaderSelector {

    private final Map<String, BooleanSupplier> health;
    private volatile String leader;

    public LeaderSelector(Map<String, BooleanSupplier> health,
                          String initialLeader) {
        this.health = new LinkedHashMap<>(health);
        this.leader = initialLeader;
    }

    public synchronized String selectLeader() {
        if (health.containsKey(leader) && health.get(leader)
                .getAsBoolean()) {
            return leader;
        }
        for (Map.Entry<String, BooleanSupplier> entry
                : health.entrySet()) {
            if (entry.getValue().getAsBoolean()) {
                leader = entry.getKey();
                return leader;
            }
        }
        return null;
    }

    public String leader() {
        return leader;
    }

    public boolean majorityHealthy() {
        long healthy = health.entrySet().stream()
                .filter(entry -> entry.getValue().getAsBoolean())
                .count();
        return healthy * 2 > health.size();
    }
}
