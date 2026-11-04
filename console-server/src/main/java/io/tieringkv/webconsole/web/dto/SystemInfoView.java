package io.tieringkv.webconsole.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 系统信息页数据（TD-092）：`INFO` + `CONFIG GET` 的只读快照。
 *
 * @param instance  实例基本信息
 * @param server    服务端信息（版本/运行时长/端口）
 * @param clients   连接信息
 * @param memory    内存信息
 * @param stats     统计信息
 * @param keyspace  键空间信息
 * @param config    白名单配置（只读）
 * @param warnings  拉取失败的段落（部分成功时非空）
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

    public record Server(String version, long uptimeSeconds, int tcpPort,
                         String os, String arch, String processId) {
    }

    public record Clients(int connected, int blocked) {
    }

    public record Memory(long usedBytes, long maxBytes, long peakBytes) {
    }

    public record Stats(long totalConnections, long totalCommands,
                        long keyspaceHits, long keyspaceMisses, double hitRate) {
    }

    public record Keyspace(String db, long keys, long expires, long avgTtl) {
    }
}
