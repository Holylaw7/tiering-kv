package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
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
        List<KeyEntry> keys = new ArrayList<>();
        if (array.items().get(1) instanceof RespReply.Array keyArray && !keyArray.isNil()) {
            for (RespReply item : keyArray.items()) {
                String key = text(item);
                keys.add(new KeyEntry(key, type(instance, key), ttl(instance, key)));
            }
        }
        boolean finished = "0".equals(nextCursor);
        return new KeyScanPage(nextCursor, keys, finished);
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
        execute(instance, "SET", key, value);
        auditService.record("KEY_SET", instance.getName() + "/" + key, null, true);
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
            case "stream" -> toRaw(execute(instance, "XRANGE", key, "-", "+", "COUNT", "50"));
            case "timeseries" -> toRaw(execute(instance, "TS.RANGE", key, "-", "+"));
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

    private Object toRaw(RespReply reply) {
        return reply;
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
