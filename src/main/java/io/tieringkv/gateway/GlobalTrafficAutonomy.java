package io.tieringkv.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全球流量自治（ADR-0157）：多地域配额联合调整（限幅 + 回滚）。
 */
public final class GlobalTrafficAutonomy {

    /** 单地域调整结果。 */
    public record RegionAdjustment(String region, boolean applied,
                                   String reason) {
    }

    private final AutonomousTrafficController controller;
    private final List<String> regions;

    public GlobalTrafficAutonomy(
            AutonomousTrafficController controller,
            List<String> regions) {
        this.controller = controller;
        this.regions = List.copyOf(regions);
    }

    /** 联合调整：每个已配置地域按目标配额调整。 */
    public List<RegionAdjustment> adjustAll(
            Map<String, Long> targets) {
        if (targets == null) {
            throw new IllegalArgumentException(
                    "targets required");
        }
        List<RegionAdjustment> results = new ArrayList<>();
        for (String region : regions) {
            Long target = targets.get(region);
            if (target == null) {
                continue;
            }
            AutonomousTrafficController.Adjustment adjustment =
                    controller.adjust(region, target);
            results.add(new RegionAdjustment(region,
                    adjustment.outcome()
                            == AutonomousTrafficController.Outcome.APPLIED,
                    adjustment.reason()));
        }
        return results;
    }

    /** 全量回滚：恢复所有已调整地域的原始配额。 */
    public void rollbackAll() {
        controller.rollback();
    }

    public boolean circuitOpen() {
        return controller.circuitOpen();
    }
}
