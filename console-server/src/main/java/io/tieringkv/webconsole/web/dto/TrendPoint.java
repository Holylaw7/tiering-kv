package io.tieringkv.webconsole.web.dto;

/** 趋势采样点（同一时间戳的多指标）。 */
public record TrendPoint(String time, double qps, double avgLatencyMs,
                         int connections, long usedMemory, long totalKeys,
                         long errors, long maxMemory) {
}
