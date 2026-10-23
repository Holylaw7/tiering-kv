package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.MetricPoint;
import io.tieringkv.webconsole.support.InfoParser;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** INFO 文本 → 指标模型 / 规则求值用的指标字典。 */
public final class MetricMapper {

    private MetricMapper() {
    }

    public static MetricPoint toPoint(Long instanceId, Map<String, Map<String, String>> sections,
                                      LocalDateTime collectedAt) {
        MetricPoint point = new MetricPoint();
        point.setInstanceId(instanceId);
        point.setCollectedAt(collectedAt);
        point.setQps(InfoParser.doubleValue(sections, "Stats", "qps",
                InfoParser.doubleValue(sections, "Server", "qps", 0)));
        point.setOpsPerSec(InfoParser.doubleValue(sections, "Stats",
                "instantaneous_ops_per_sec",
                InfoParser.doubleValue(sections, "Server", "qps", 0)));
        point.setAvgLatencyMs(InfoParser.doubleValue(sections, "Stats",
                "avg_latency_ms",
                InfoParser.doubleValue(sections, "Server", "avg_latency_ms", 0)));
        point.setConnections((int) InfoParser.longValue(sections, "Clients",
                "connected_clients",
                InfoParser.longValue(sections, "Server", "connections", 0)));
        point.setActiveRequests((int) InfoParser.longValue(sections, "Clients",
                "active_requests",
                InfoParser.longValue(sections, "Server", "active_requests", 0)));
        point.setErrors(InfoParser.longValue(sections, "Stats", "errors",
                InfoParser.longValue(sections, "Server", "errors", 0)));
        point.setUsedMemory(InfoParser.longValue(sections, "Memory", "used_memory", 0));
        point.setMaxMemory(InfoParser.longValue(sections, "Memory", "maxmemory", 0));
        point.setTotalKeys(InfoParser.keyCount(sections));
        point.setUptimeSeconds(InfoParser.longValue(sections, "Server", "uptime_in_seconds", 0));
        return point;
    }

    /** 告警规则可引用的指标字典。 */
    public static Map<String, Double> metricDictionary(MetricPoint point) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("qps", value(point.getQps()));
        metrics.put("ops_per_sec", value(point.getOpsPerSec()));
        metrics.put("avg_latency_ms", value(point.getAvgLatencyMs()));
        metrics.put("connections", value(point.getConnections()));
        metrics.put("active_requests", value(point.getActiveRequests()));
        metrics.put("errors", value(point.getErrors()));
        metrics.put("used_memory", value(point.getUsedMemory()));
        metrics.put("max_memory", value(point.getMaxMemory()));
        metrics.put("total_keys", value(point.getTotalKeys()));
        metrics.put("uptime_seconds", value(point.getUptimeSeconds()));
        long max = point.getMaxMemory() == null ? 0 : point.getMaxMemory();
        long used = point.getUsedMemory() == null ? 0 : point.getUsedMemory();
        metrics.put("used_memory_ratio", max <= 0 ? 0 : (double) used / max);
        return metrics;
    }

    private static double value(Number number) {
        return number == null ? 0 : number.doubleValue();
    }
}
