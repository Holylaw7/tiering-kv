package io.tieringkv.webconsole.web.dto;

/** 实时指标（由一次 INFO 现场解析，不落库）。 */
public record LiveMetrics(String version, long uptimeSeconds, long usedMemory,
                          long maxMemory, long usedMemoryJvm, long totalKeys,
                          int connections, int activeRequests, double qps,
                          double opsPerSec, double avgLatencyMs, long errors,
                          long maxJvmHeap) {
}
