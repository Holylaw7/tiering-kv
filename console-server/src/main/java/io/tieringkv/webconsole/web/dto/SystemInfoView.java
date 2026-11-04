package io.tieringkv.webconsole.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 系统信息页数据（TD-092）：`INFO` + `CONFIG GET` 的只读快照。
 *
 * <p>字段按 Tiering-KV 的 INFO 段落设计（与 Redis 有差异的地方保留回退，
 * 例如版本优先取 `tieringkv_version`）。
 */
public record SystemInfoView(
        InstanceView instance,
        Server server,
        Clients clients,
        Memory memory,
        Stats stats,
        List<Keyspace> keyspace,
        Map<String, String> config,
        List<String> warnings) {

    public record Server(String version, String mode, long uptimeSeconds, int tcpPort,
                         String os, String arch, String jdk, String processId) {
    }

    public record Clients(int connected, int activeRequests, long totalConnections, long errors) {
    }

    public record Memory(long usedBytes, long maxBytes, long jvmUsedBytes, long maxJvmHeapBytes) {
    }

    public record Stats(double qps, double instantaneousOpsPerSec, double avgLatencyMs, long totalKeys) {
    }

    public record Keyspace(String db, long keys, long expires, long avgTtl) {
    }
}
