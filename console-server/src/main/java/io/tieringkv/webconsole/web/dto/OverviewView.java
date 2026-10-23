package io.tieringkv.webconsole.web.dto;

import java.util.List;

/** 仪表盘总览。 */
public record OverviewView(List<InstanceView> instances, InstanceView selected,
                           LiveMetrics live, long totalKeys, long firingAlerts,
                           long resolvedToday, long uptimeSeconds) {
}
