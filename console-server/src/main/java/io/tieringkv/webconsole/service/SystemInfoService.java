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
 * <p>字段名以 Tiering-KV 的 INFO 为准（见 `ServerInfoProvider`），
 * Redis 同名段落有差异的字段保留回退取值。
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

        String version = firstNonBlank(server.get("tieringkv_version"), server.get("redis_version"));

        return new SystemInfoView(
                InstanceView.of(instance),
                new SystemInfoView.Server(
                        nullToEmpty(version),
                        nullToEmpty(server.get("redis_mode")),
                        number(server.get("uptime_in_seconds")),
                        (int) number(server.getOrDefault("tcp_port", "0")),
                        nullToEmpty(server.get("os")),
                        nullToEmpty(server.get("arch_bits")),
                        nullToEmpty(server.get("jdk_version")),
                        nullToEmpty(server.get("process_id"))),
                new SystemInfoView.Clients(
                        (int) number(clients.get("connected_clients")),
                        (int) number(clients.get("active_requests")),
                        number(stats.get("connections")),
                        number(stats.get("errors"))),
                new SystemInfoView.Memory(
                        number(memory.get("used_memory")),
                        number(memory.getOrDefault("maxmemory",
                                config.getOrDefault("maxmemory", "0"))),
                        number(memory.get("used_memory_jvm")),
                        number(memory.get("max_jvm_heap"))),
                new SystemInfoView.Stats(
                        decimal(stats.get("qps")),
                        decimal(stats.get("instantaneous_ops_per_sec")),
                        decimal(stats.get("avg_latency_ms")),
                        number(stats.get("total_keys"))),
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

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static long number(String value) {
        return (long) decimal(value);
    }

    private static double decimal(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
