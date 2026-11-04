package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.support.InfoParser;
import io.tieringkv.webconsole.web.dto.InstanceView;
import io.tieringkv.webconsole.web.dto.SystemInfoView;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统信息只读快照（TD-092）：`INFO` 全部段落 + `CONFIG GET *`。
 *
 * <p>只读：CONFIG 不支持 SET（内核白名单里也只有 GET/SET 的读语义在控制台侧禁用）。
 */
@Service
public class SystemInfoService {

    private final InstanceService instanceService;
    private final RespGateway gateway;

    public SystemInfoService(InstanceService instanceService, RespGateway gateway) {
        this.instanceService = instanceService;
        this.gateway = gateway;
    }

    public SystemInfoView snapshot(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        List<String> warnings = new ArrayList<>();

        Map<String, Map<String, String>> info = parseInfo(instance, warnings);
        Map<String, String> config = readConfig(instance, warnings);

        Map<String, String> server = info.getOrDefault("Server", Map.of());
        Map<String, String> clients = info.getOrDefault("Clients", Map.of());
        Map<String, String> memory = info.getOrDefault("Memory", Map.of());
        Map<String, String> stats = info.getOrDefault("Stats", Map.of());

        long hits = number(stats.get("keyspace_hits"));
        long misses = number(stats.get("keyspace_misses"));
        double hitRate = hits + misses == 0 ? 0 : (double) hits / (hits + misses);

        return new SystemInfoView(
                InstanceView.of(instance),
                new SystemInfoView.Server(
                        server.getOrDefault("redis_version", ""),
                        number(server.get("uptime_in_seconds")),
                        (int) number(server.getOrDefault("tcp_port", "0")),
                        server.getOrDefault("os", ""),
                        server.getOrDefault("arch_bits", ""),
                        server.getOrDefault("process_id", "")),
                new SystemInfoView.Clients(
                        (int) number(clients.get("connected_clients")),
                        (int) number(clients.get("blocked_clients"))),
                new SystemInfoView.Memory(
                        number(memory.get("used_memory")),
                        number(memory.getOrDefault("maxmemory", config.getOrDefault("maxmemory", "0"))),
                        number(memory.get("used_memory_peak"))),
                new SystemInfoView.Stats(
                        number(stats.get("total_connections_received")),
                        number(stats.get("total_commands_processed")),
                        hits, misses, Math.round(hitRate * 10000) / 10000.0),
                keyspace(info.getOrDefault("Keyspace", Map.of())),
                config,
                warnings);
    }

    private Map<String, Map<String, String>> parseInfo(DbInstance instance, List<String> warnings) {
        try {
            RespReply reply = gateway.execute(instance, "INFO");
            String text = reply instanceof RespReply.Bulk bulk ? bulk.utf8() : null;
            if (text == null) {
                warnings.add("INFO 返回为空");
                return Map.of();
            }
            return InfoParser.parse(text);
        } catch (IOException ex) {
            throw BusinessException.upstream("读取 INFO 失败：" + ex.getMessage());
        }
    }

    private Map<String, String> readConfig(DbInstance instance, List<String> warnings) {
        Map<String, String> config = new LinkedHashMap<>();
        try {
            RespReply reply = gateway.execute(instance, "CONFIG", "GET", "*");
            if (reply instanceof RespReply.Array array && !array.isNil()) {
                List<RespReply> items = array.items();
                for (int i = 0; i + 1 < items.size(); i += 2) {
                    String key = text(items.get(i));
                    String value = text(items.get(i + 1));
                    if (key != null) {
                        config.put(key, value);
                    }
                }
            } else {
                warnings.add("CONFIG GET 返回结构异常");
            }
        } catch (IOException ex) {
            warnings.add("读取 CONFIG 失败：" + ex.getMessage());
        }
        return config;
    }

    private static List<SystemInfoView.Keyspace> keyspace(Map<String, String> section) {
        List<SystemInfoView.Keyspace> result = new ArrayList<>();
        section.forEach((db, raw) -> {
            long keys = 0;
            long expires = 0;
            long avgTtl = 0;
            for (String part : raw.split(",")) {
                String[] kv = part.split("=");
                if (kv.length != 2) {
                    continue;
                }
                switch (kv[0].trim()) {
                    case "keys" -> keys = number(kv[1]);
                    case "expires" -> expires = number(kv[1]);
                    case "avg_ttl" -> avgTtl = number(kv[1]);
                    default -> {
                    }
                }
            }
            result.add(new SystemInfoView.Keyspace(db, keys, expires, avgTtl));
        });
        result.sort((a, b) -> a.db().compareTo(b.db()));
        return result;
    }

    private static String text(RespReply reply) {
        return reply instanceof RespReply.Bulk bulk ? bulk.utf8() : null;
    }

    private static long number(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return (long) Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
