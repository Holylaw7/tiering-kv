package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespClient;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.support.Text;
import io.tieringkv.webconsole.web.dto.KeyDetail;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import io.tieringkv.webconsole.web.dto.KeyScanPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 键空间浏览与维护。 */
@Service
public class KeyspaceService {

    private static final int MAX_COUNT = 200;

    private final InstanceService instanceService;
    private final RespGateway gateway;
    private final AuditService auditService;

    public KeyspaceService(InstanceService instanceService, RespGateway gateway,
                           AuditService auditService) {
        this.instanceService = instanceService;
        this.gateway = gateway;
        this.auditService = auditService;
    }

    public KeyScanPage scan(Long instanceId, String cursor, String pattern, int count) {
        DbInstance instance = instanceService.require(instanceId);
        String effectiveCursor = cursor == null || cursor.isBlank() ? "0" : cursor;
        String effectivePattern = pattern == null || pattern.isBlank() ? "*" : pattern;
        int effectiveCount = Math.min(MAX_COUNT, Math.max(1, count));
        RespReply reply;
        try {
            reply = gateway.execute(instance, "SCAN", effectiveCursor,
                    "MATCH", effectivePattern, "COUNT", String.valueOf(effectiveCount));
        } catch (IOException ex) {
            throw upstream(instance, ex);
        }
        if (reply instanceof RespReply.Error error) {
            throw BusinessException.badRequest("SCAN 失败：" + error.message());
        }
        if (!(reply instanceof RespReply.Array array) || array.isNil() || array.items().size() < 2) {
            throw BusinessException.upstream("SCAN 返回结构异常");
        }
        String nextCursor = text(array.items().get(0));
        List<String> keyNames = new ArrayList<>();
        if (array.items().get(1) instanceof RespReply.Array keyArray && !keyArray.isNil()) {
            for (RespReply item : keyArray.items()) {
                String key = text(item);
                if (key != null) {
                    keyNames.add(key);
                }
            }
        }
        List<KeyEntry> keys = inspectBatch(instance, keyNames);
        boolean finished = "0".equals(nextCursor);
        return new KeyScanPage(nextCursor, keys, finished);
    }

    /**
     * RESP Pipeline 批量探测键元数据（O2-A）：整批键的 TYPE + TTL 合并为单次网络往返。
     */
    public List<KeyEntry> inspectBatch(DbInstance instance, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<List<byte[]>> commands = new ArrayList<>(keys.size() * 2);
        for (String key : keys) {
            commands.add(RespClient.commandBytes("TYPE", key));
            commands.add(RespClient.commandBytes("TTL", key));
        }
        List<RespReply> replies;
        try {
            replies = gateway.executeBatch(instance, commands);
        } catch (IOException ex) {
            throw upstream(instance, ex);
        }
        if (replies.size() != commands.size()) {
            throw BusinessException.upstream("管线批量探测返回条目数不匹配：期望 "
                    + commands.size() + "，实际 " + replies.size());
        }
        List<KeyEntry> result = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            String type = text(replies.get(i * 2));
            RespReply ttlReply = replies.get(i * 2 + 1);
            long ttl = ttlReply instanceof RespReply.Integer integer ? integer.value() : -2;
            result.add(new KeyEntry(keys.get(i), type, ttl));
        }
        return result;
    }

    /**
     * RESP Pipeline 批量读取键值（O2-A）：将同批标准类型键的取值命令合并为单次往返。
     */
    public List<Object> readValuesBatch(DbInstance instance, List<KeyEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<List<byte[]>> commands = new ArrayList<>(entries.size());
        List<Integer> commandIndices = new ArrayList<>(entries.size());
        Object[] values = new Object[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            KeyEntry entry = entries.get(i);
            List<byte[]> cmd = valueReadCommand(entry.key(), entry.type());
            if (cmd != null) {
                commands.add(cmd);
                commandIndices.add(i);
            } else if ("stream".equals(entry.type())) {
                values[i] = safeDump(instance, "XRANGE", entry.key(), "-", "+", "COUNT", "50");
            } else if ("timeseries".equals(entry.type())) {
                values[i] = safeDump(instance, "TS.RANGE", entry.key(), "-", "+");
            }
        }
        if (!commands.isEmpty()) {
            List<RespReply> replies;
            try {
                replies = gateway.executeBatch(instance, commands);
            } catch (IOException ex) {
                throw upstream(instance, ex);
            }
            if (replies.size() != commands.size()) {
                throw BusinessException.upstream("管线批量取值返回条目数不匹配：期望 "
                        + commands.size() + "，实际 " + replies.size());
            }
            for (int j = 0; j < commandIndices.size(); j++) {
                int idx = commandIndices.get(j);
                RespReply reply = replies.get(j);
                if (reply instanceof RespReply.Error error) {
                    throw BusinessException.badRequest("命令执行失败：" + error.message());
                }
                values[idx] = decodeValue(entries.get(idx).type(), reply);
            }
        }
        List<Object> out = new ArrayList<>(entries.size());
        for (Object v : values) {
            out.add(v);
        }
        return out;
    }

    private List<byte[]> valueReadCommand(String key, String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "string" -> RespClient.commandBytes("GET", key);
            case "hash" -> RespClient.commandBytes("HGETALL", key);
            case "list" -> RespClient.commandBytes("LRANGE", key, "0", "-1");
            case "set" -> RespClient.commandBytes("SMEMBERS", key);
            case "zset" -> RespClient.commandBytes("ZRANGE", key, "0", "-1", "WITHSCORES");
            case "json" -> RespClient.commandBytes("JSON.GET", key);
            default -> null;
        };
    }

    private Object decodeValue(String type, RespReply reply) {
        return switch (type) {
            case "string", "json" -> text(reply);
            case "hash" -> toMap(reply);
            case "list", "set" -> toList(reply);
            case "zset" -> toScoredList(reply);
            default -> null;
        };
    }

    public KeyDetail detail(Long instanceId, String key) {
        DbInstance instance = instanceService.require(instanceId);
        String type = type(instance, key);
        long ttl = ttl(instance, key);
        if ("none".equals(type)) {
            throw BusinessException.notFound("键不存在：" + key);
        }
        return new KeyDetail(key, type, ttl, value(instance, key, type));
    }

    @Transactional
    public void delete(Long instanceId, String key) {
        DbInstance instance = instanceService.require(instanceId);
        RespReply reply = execute(instance, "DEL", key);
        auditService.record("KEY_DELETE", instance.getName() + "/" + key,
                "deleted=" + integer(reply), true);
    }

    @Transactional
    public void expire(Long instanceId, String key, long ttlSeconds) {
        DbInstance instance = instanceService.require(instanceId);
        if (ttlSeconds <= 0) {
            execute(instance, "PERSIST", key);
        } else {
            execute(instance, "EXPIRE", key, String.valueOf(ttlSeconds));
        }
        auditService.record("KEY_EXPIRE", instance.getName() + "/" + key,
                "ttl=" + ttlSeconds, true);
    }

    @Transactional
    public void setString(Long instanceId, String key, String value) {
        DbInstance instance = instanceService.require(instanceId);
        String type = type(instance, key);
        if (!"none".equals(type) && !"string".equals(type)) {
            throw BusinessException.badRequest(
                    "键 " + key + " 的类型为 " + type + "，不能用 SET 覆盖（请使用对应类型的编辑入口）");
        }
        execute(instance, "SET", key, value);
        auditService.record("KEY_SET", instance.getName() + "/" + key, null, true);
    }

    /** hash 单字段写入（TD-089）：不存在则新建，存在则覆盖。 */
    @Transactional
    public void setHashField(Long instanceId, String key, String field, String value) {
        DbInstance instance = instanceService.require(instanceId);
        String type = type(instance, key);
        if (!"none".equals(type) && !"hash".equals(type)) {
            throw BusinessException.badRequest(
                    "键 " + key + " 的类型为 " + type + "，不能写入 hash 字段");
        }
        RespReply reply = execute(instance, "HSET", key, field, value);
        if (reply instanceof RespReply.Error error) {
            throw BusinessException.badRequest("HSET 失败：" + error.message());
        }
        auditService.record("KEY_HSET", instance.getName() + "/" + key + "/" + field, null, true);
    }

    /** hash 单字段删除（TD-089）：字段不存在返回 404。 */
    @Transactional
    public void deleteHashField(Long instanceId, String key, String field) {
        DbInstance instance = instanceService.require(instanceId);
        String type = type(instance, key);
        if (!"hash".equals(type)) {
            throw BusinessException.badRequest("键 " + key + " 的类型为 " + type + "，不是 hash");
        }
        RespReply reply = execute(instance, "HDEL", key, field);
        if (integer(reply) == 0) {
            throw BusinessException.notFound("字段不存在：" + field);
        }
        auditService.record("KEY_HDEL", instance.getName() + "/" + key + "/" + field, null, true);
    }

    public String type(DbInstance instance, String key) {
        RespReply reply = execute(instance, "TYPE", key);
        return text(reply);
    }

    public long ttl(DbInstance instance, String key) {
        RespReply reply = execute(instance, "TTL", key);
        return reply instanceof RespReply.Integer integer ? integer.value() : -2;
    }

    Object value(DbInstance instance, String key, String type) {
        return switch (type) {
            case "string" -> text(execute(instance, "GET", key));
            case "hash" -> toMap(execute(instance, "HGETALL", key));
            case "list" -> toList(execute(instance, "LRANGE", key, "0", "-1"));
            case "set" -> toList(execute(instance, "SMEMBERS", key));
            case "zset" -> toScoredList(execute(instance, "ZRANGE", key, "0", "-1", "WITHSCORES"));
            case "json" -> text(execute(instance, "JSON.GET", key));
            case "stream" -> safeDump(instance, "XRANGE", key, "-", "+", "COUNT", "50");
            case "timeseries" -> safeDump(instance, "TS.RANGE", key, "-", "+");
            default -> null;
        };
    }

    private Map<String, String> toMap(RespReply reply) {
        Map<String, String> map = new LinkedHashMap<>();
        if (reply instanceof RespReply.Array array && !array.isNil()) {
            List<RespReply> items = array.items();
            for (int i = 0; i + 1 < items.size(); i += 2) {
                map.put(text(items.get(i)), text(items.get(i + 1)));
            }
        }
        return map;
    }

    private List<String> toList(RespReply reply) {
        List<String> list = new ArrayList<>();
        if (reply instanceof RespReply.Array array && !array.isNil()) {
            array.items().forEach(item -> list.add(text(item)));
        }
        return list;
    }

    private List<Map<String, String>> toScoredList(RespReply reply) {
        List<Map<String, String>> result = new ArrayList<>();
        if (reply instanceof RespReply.Array array && !array.isNil()) {
            List<RespReply> items = array.items();
            for (int i = 0; i + 1 < items.size(); i += 2) {
                result.add(Map.of("member", text(items.get(i)), "score", text(items.get(i + 1))));
            }
        }
        return result;
    }

    /** 多模型类型的只读转储：命令不支持时降级为说明对象，不向前端抛 500。 */
    private Object safeDump(DbInstance instance, String... args) {
        try {
            RespReply reply = gateway.execute(instance, args);
            if (reply instanceof RespReply.Error error) {
                return Map.of("unsupported", error.message());
            }
            return reply;
        } catch (IOException ex) {
            return Map.of("unsupported", "读取失败：" + ex.getMessage());
        }
    }

    private RespReply execute(DbInstance instance, String... args) {
        try {
            RespReply reply = gateway.execute(instance, args);
            if (reply instanceof RespReply.Error error) {
                throw BusinessException.badRequest("命令执行失败：" + error.message());
            }
            return reply;
        } catch (IOException ex) {
            throw upstream(instance, ex);
        }
    }

    private String text(RespReply reply) {
        return switch (reply) {
            case RespReply.Bulk bulk -> Text.decode(bulk.value());
            case RespReply.Simple simple -> simple.value();
            case RespReply.Integer integer -> String.valueOf(integer.value());
            case RespReply.Error error -> throw BusinessException.badRequest(error.message());
            case RespReply.Array array -> array.isNil() ? null : array.items().toString();
        };
    }

    private long integer(RespReply reply) {
        return reply instanceof RespReply.Integer integer ? integer.value() : 0;
    }

    private BusinessException upstream(DbInstance instance, IOException ex) {
        return BusinessException.upstream("实例 " + instance.getName() + " 通信失败：" + ex.getMessage());
    }
}
