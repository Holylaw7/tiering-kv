package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonArrAppendRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonKeysView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonNumIncrRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonQueryView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonSetRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesAddRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesIncrByRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesKeysView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesPoint;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesRangeView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesSummary;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorAddRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorCollectionSummary;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorCollectionsView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorSearchHit;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorSearchRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorSearchResultView;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多模型工作台服务（v6.2.0 C1）：基于 Tiering-KV 既有 26 条 RESP 命令提供
 * Vector HNSW、TimeSeries 时序分析与 JSON Path 操作；对不支持多模型命令的普通 Redis
 * 优雅返回 {@code supported=false}。
 */
@Service
public class MultiModelService {

    private static final String DEFAULT_COLLECTION = "default";
    private static final String UNSUPPORTED_VECTOR_MSG = "当前实例未启用 Tiering-KV 向量索引命令（VECTOR.*）";
    private static final String UNSUPPORTED_TS_MSG = "当前实例未启用 Tiering-KV 时序命令（TS.*）";
    private static final String UNSUPPORTED_JSON_MSG = "当前实例未启用 Tiering-KV JSON 路径命令（JSON.*）";
    private static final Set<String> VALID_AGGREGATIONS =
            Set.of("AVG", "SUM", "MIN", "MAX", "COUNT", "FIRST", "LAST");

    private final InstanceService instanceService;
    private final RespGateway gateway;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public MultiModelService(InstanceService instanceService,
                             RespGateway gateway,
                             AuditService auditService,
                             ObjectMapper objectMapper) {
        this.instanceService = instanceService;
        this.gateway = gateway;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ==================== Vector HNSW ====================

    public VectorCollectionsView listVectorCollections(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        RespReply reply = executeOrThrow(instance, "VECTOR.LIST");
        if (isUnknownCommand(reply)) {
            return new VectorCollectionsView(false, UNSUPPORTED_VECTOR_MSG, List.of(), 0L);
        }
        if (reply instanceof RespReply.Error err) {
            throw BusinessException.badRequest("读取向量集合失败：" + err.message());
        }
        List<VectorCollectionSummary> collections = new ArrayList<>();
        long total = 0L;
        if (reply instanceof RespReply.Array arr && !arr.isNil()) {
            for (RespReply item : arr.items()) {
                if (item instanceof RespReply.Array pair && !pair.isNil() && pair.items().size() >= 2) {
                    String name = asString(pair.items().get(0));
                    long count = asLong(pair.items().get(1), 0L);
                    if (name != null) {
                        collections.add(new VectorCollectionSummary(name, count));
                        total += count;
                    }
                }
            }
        }
        return new VectorCollectionsView(true, null, collections, total);
    }

    public void addVector(VectorAddRequest request) {
        DbInstance instance = instanceService.require(request.instanceId());
        String collection = normalizeCollection(request.collection());
        String id = request.id().trim();
        validateFiniteNumbers(request.values(), "向量分量");

        List<String> args = new ArrayList<>(5 + request.values().size());
        args.add("VECTOR.ADD");
        if (!DEFAULT_COLLECTION.equals(collection)) {
            args.add("COLLECTION");
            args.add(collection);
        }
        args.add(id);
        args.add(String.valueOf(request.values().size()));
        for (Double val : request.values()) {
            args.add(String.valueOf(val));
        }

        RespReply reply = executeOrThrow(instance, args.toArray(new String[0]));
        assertNotError(reply, "写入向量失败");
        auditService.record("MULTIMODEL_VECTOR_ADD", instance.getName() + "/" + collection + "/" + id,
                "dim=" + request.values().size(), true);
    }

    public VectorSearchResultView searchVector(VectorSearchRequest request) {
        DbInstance instance = instanceService.require(request.instanceId());
        String collection = normalizeCollection(request.collection());
        validateFiniteNumbers(request.vector(), "查询向量");
        int topK = request.topK() == null ? 10 : request.topK();

        List<String> args = new ArrayList<>(6 + request.vector().size());
        args.add("VECTOR.SEARCH");
        if (!DEFAULT_COLLECTION.equals(collection)) {
            args.add("COLLECTION");
            args.add(collection);
        }
        args.add(String.valueOf(request.vector().size()));
        for (Double val : request.vector()) {
            args.add(String.valueOf(val));
        }
        args.add("TOPK");
        args.add(String.valueOf(topK));

        long started = System.nanoTime();
        RespReply reply = executeOrThrow(instance, args.toArray(new String[0]));
        long costMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        if (isUnknownCommand(reply)) {
            return new VectorSearchResultView(false, UNSUPPORTED_VECTOR_MSG, collection, topK, costMs, List.of());
        }
        assertNotError(reply, "向量检索失败");

        List<VectorSearchHit> hits = new ArrayList<>();
        if (reply instanceof RespReply.Array arr && !arr.isNil()) {
            for (RespReply item : arr.items()) {
                if (item instanceof RespReply.Array pair && !pair.isNil() && pair.items().size() >= 2) {
                    String hitId = asString(pair.items().get(0));
                    double score = asDouble(pair.items().get(1), 0.0);
                    if (hitId != null) {
                        hits.add(new VectorSearchHit(hitId, score));
                    }
                }
            }
        }
        return new VectorSearchResultView(true, null, collection, topK, costMs, hits);
    }

    public boolean deleteVector(Long instanceId, String collection, String id) {
        DbInstance instance = instanceService.require(instanceId);
        if (id == null || id.isBlank()) {
            throw BusinessException.badRequest("向量 ID 不能为空");
        }
        String col = normalizeCollection(collection);
        RespReply reply = DEFAULT_COLLECTION.equals(col)
                ? executeOrThrow(instance, "VECTOR.DEL", id.trim())
                : executeOrThrow(instance, "VECTOR.DEL", "COLLECTION", col, id.trim());
        assertNotError(reply, "删除向量失败");
        boolean deleted = asLong(reply, 0L) > 0;
        auditService.record("MULTIMODEL_VECTOR_DEL", instance.getName() + "/" + col + "/" + id.trim(),
                "deleted=" + deleted, true);
        return deleted;
    }

    public boolean dropVectorCollection(Long instanceId, String collection) {
        DbInstance instance = instanceService.require(instanceId);
        if (collection == null || collection.isBlank()) {
            throw BusinessException.badRequest("集合名称不能为空");
        }
        String col = collection.trim();
        RespReply reply = executeOrThrow(instance, "VECTOR.DROP", col);
        assertNotError(reply, "删除向量集合失败");
        boolean dropped = asLong(reply, 0L) > 0;
        auditService.record("MULTIMODEL_VECTOR_DROP", instance.getName() + "/" + col,
                "dropped=" + dropped, true);
        return dropped;
    }

    public String checkpointVector(Long instanceId, String collection) {
        DbInstance instance = instanceService.require(instanceId);
        String col = collection == null || collection.isBlank() ? null : collection.trim();
        RespReply reply = col == null
                ? executeOrThrow(instance, "VECTOR.CHECKPOINT")
                : executeOrThrow(instance, "VECTOR.CHECKPOINT", "COLLECTION", col);
        assertNotError(reply, "向量索引落盘失败");
        String result = asString(reply);
        auditService.record("MULTIMODEL_VECTOR_CHECKPOINT",
                instance.getName() + "/" + (col == null ? "*" : col), result, true);
        return result == null ? "OK" : result;
    }

    // ==================== TimeSeries ====================

    public TimeSeriesKeysView listTimeSeries(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        RespReply reply = executeOrThrow(instance, "TS.MRANGE", "-inf", "+inf");
        if (isUnknownCommand(reply)) {
            return new TimeSeriesKeysView(false, UNSUPPORTED_TS_MSG, List.of());
        }
        assertNotError(reply, "读取时序列表失败");

        List<TimeSeriesSummary> summaries = new ArrayList<>();
        if (reply instanceof RespReply.Array arr && !arr.isNil()) {
            for (RespReply item : arr.items()) {
                if (item instanceof RespReply.Array pair && !pair.isNil() && pair.items().size() >= 2) {
                    String key = asString(pair.items().get(0));
                    List<TimeSeriesPoint> pts = parseTimeSeriesPoints(pair.items().get(1));
                    if (key != null) {
                        TimeSeriesPoint last = pts.isEmpty() ? null : pts.get(pts.size() - 1);
                        summaries.add(new TimeSeriesSummary(
                                key, pts.size(),
                                last == null ? null : last.timestamp(),
                                last == null ? null : last.value()));
                    }
                }
            }
        }
        return new TimeSeriesKeysView(true, null, summaries);
    }

    public TimeSeriesPoint addTimeSeriesPoint(TimeSeriesAddRequest request) {
        DbInstance instance = instanceService.require(request.instanceId());
        if (!Double.isFinite(request.value())) {
            throw BusinessException.badRequest("采样数值必须为有限实数");
        }
        String key = request.key().trim();
        long ts = request.timestamp() != null ? request.timestamp() : System.currentTimeMillis();
        RespReply reply = executeOrThrow(instance, "TS.ADD", key, String.valueOf(ts), String.valueOf(request.value()));
        assertNotError(reply, "写入时序数据点失败");
        auditService.record("MULTIMODEL_TS_ADD", instance.getName() + "/" + key,
                "ts=" + ts + ",val=" + request.value(), true);
        return new TimeSeriesPoint(ts, request.value());
    }

    public long incrTimeSeries(TimeSeriesIncrByRequest request) {
        DbInstance instance = instanceService.require(request.instanceId());
        if (!Double.isFinite(request.delta())) {
            throw BusinessException.badRequest("增量值必须为有限实数");
        }
        String key = request.key().trim();
        RespReply reply = request.timestamp() == null
                ? executeOrThrow(instance, "TS.INCRBY", key, String.valueOf(request.delta()))
                : executeOrThrow(instance, "TS.INCRBY", key, String.valueOf(request.delta()),
                        "TIMESTAMP", String.valueOf(request.timestamp()));
        assertNotError(reply, "累加时序数据失败");
        long ts = asLong(reply, request.timestamp() == null ? System.currentTimeMillis() : request.timestamp());
        auditService.record("MULTIMODEL_TS_INCRBY", instance.getName() + "/" + key,
                "delta=" + request.delta() + ",ts=" + ts, true);
        return ts;
    }

    public TimeSeriesRangeView queryTimeSeriesRange(Long instanceId, String key, String from, String to,
                                                    String aggregation, Long bucketMs, Integer count) {
        DbInstance instance = instanceService.require(instanceId);
        if (key == null || key.isBlank()) {
            throw BusinessException.badRequest("时序键名不能为空");
        }
        String k = key.trim();
        String effectiveFrom = (from == null || from.isBlank()) ? "-inf" : from.trim();
        String effectiveTo = (to == null || to.isBlank()) ? "+inf" : to.trim();
        String normAgg = normalizeAggregation(aggregation);

        List<String> rangeArgs = new ArrayList<>();
        rangeArgs.add("TS.RANGE");
        rangeArgs.add(k);
        rangeArgs.add(effectiveFrom);
        rangeArgs.add(effectiveTo);
        if (normAgg != null) {
            long effectiveBucket = (bucketMs == null || bucketMs <= 0) ? 60_000L : bucketMs;
            rangeArgs.add("AGGREGATION");
            rangeArgs.add(normAgg);
            rangeArgs.add(String.valueOf(effectiveBucket));
            bucketMs = effectiveBucket;
        }
        if (count != null && count > 0) {
            rangeArgs.add("COUNT");
            rangeArgs.add(String.valueOf(Math.min(count, 5000)));
        }

        RespReply rangeReply = executeOrThrow(instance, rangeArgs.toArray(new String[0]));
        if (isUnknownCommand(rangeReply)) {
            return new TimeSeriesRangeView(false, UNSUPPORTED_TS_MSG, k, 0L, normAgg, bucketMs, List.of(), null);
        }
        assertNotError(rangeReply, "查询时序范围失败");
        List<TimeSeriesPoint> points = parseTimeSeriesPoints(rangeReply);

        long totalLen = points.size();
        RespReply lenReply = executeQuietly(instance, "TS.LEN", k);
        if (lenReply instanceof RespReply.Integer i) {
            totalLen = i.value();
        }

        Double reducedValue = null;
        if (normAgg != null) {
            RespReply reduceReply = executeQuietly(instance, "TS.REDUCE", k, "AGGREGATION", normAgg);
            if (reduceReply instanceof RespReply.Array arr && !arr.isNil() && arr.items().size() >= 2) {
                reducedValue = asDouble(arr.items().get(1), Double.NaN);
                if (Double.isNaN(reducedValue)) {
                    reducedValue = null;
                }
            }
        }
        return new TimeSeriesRangeView(true, null, k, totalLen, normAgg, bucketMs, points, reducedValue);
    }

    // ==================== JSON Path Explorer ====================

    public JsonKeysView listJsonKeys(Long instanceId, String pattern, int limit) {
        DbInstance instance = instanceService.require(instanceId);
        int capped = Math.min(200, Math.max(10, limit));
        String match = (pattern == null || pattern.isBlank()) ? "*" : pattern.trim();

        // 探测 JSON 命令可用性（对不存在键执行 JSON.TYPE，普通 Redis 返回 unknown command）
        RespReply probe = executeOrThrow(instance, "JSON.TYPE", "__tk_json_probe__");
        if (isUnknownCommand(probe)) {
            return new JsonKeysView(false, UNSUPPORTED_JSON_MSG, List.of());
        }

        RespReply scanReply = executeOrThrow(instance, "SCAN", "0", "MATCH", match, "COUNT", String.valueOf(capped));
        if (!(scanReply instanceof RespReply.Array outer) || outer.isNil() || outer.items().size() < 2) {
            return new JsonKeysView(true, null, List.of());
        }
        RespReply keysNode = outer.items().get(1);
        if (!(keysNode instanceof RespReply.Array keyArr) || keyArr.isNil() || keyArr.items().isEmpty()) {
            return new JsonKeysView(true, null, List.of());
        }

        List<String> candidates = new ArrayList<>();
        List<List<byte[]>> typeCmds = new ArrayList<>();
        for (RespReply item : keyArr.items()) {
            String key = asString(item);
            if (key != null) {
                candidates.add(key);
                typeCmds.add(List.of(
                        "TYPE".getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8)));
            }
        }
        List<String> jsonKeys = new ArrayList<>();
        try {
            List<RespReply> types = gateway.executeBatch(instance, typeCmds);
            for (int i = 0; i < Math.min(candidates.size(), types.size()); i++) {
                String typeName = asString(types.get(i));
                if ("json".equalsIgnoreCase(typeName)) {
                    jsonKeys.add(candidates.get(i));
                }
            }
        } catch (IOException ex) {
            throw BusinessException.upstream("探测 JSON 键类型失败：" + ex.getMessage());
        }
        return new JsonKeysView(true, null, jsonKeys);
    }

    public JsonQueryView getJson(Long instanceId, String key, String path) {
        DbInstance instance = instanceService.require(instanceId);
        if (key == null || key.isBlank()) {
            throw BusinessException.badRequest("JSON 键名不能为空");
        }
        String k = key.trim();
        String effectivePath = (path == null || path.isBlank()) ? "$" : path.trim();

        RespReply getReply = "$".equals(effectivePath)
                ? executeOrThrow(instance, "JSON.GET", k)
                : executeOrThrow(instance, "JSON.GET", k, effectivePath);
        if (isUnknownCommand(getReply)) {
            return new JsonQueryView(false, UNSUPPORTED_JSON_MSG, false, k, effectivePath,
                    null, null, null, List.of());
        }
        assertNotError(getReply, "读取 JSON 失败");

        String raw = asString(getReply);
        if (raw == null) {
            return new JsonQueryView(true, null, false, k, effectivePath, null, null, null, List.of());
        }

        String valueType = extractFirstText(executeQuietly(instance, "JSON.TYPE", k, effectivePath));
        List<String> objKeys = List.of();
        if ("object".equalsIgnoreCase(valueType)) {
            objKeys = extractObjectKeys(executeQuietly(instance, "JSON.OBJKEYS", k, effectivePath));
        }

        String pretty = prettyPrintJson(raw);
        return new JsonQueryView(true, null, true, k, effectivePath, valueType, raw, pretty, objKeys);
    }

    public void setJson(JsonSetRequest request) {
        DbInstance instance = instanceService.require(request.instanceId());
        String key = request.key().trim();
        String path = (request.path() == null || request.path().isBlank()) ? "$" : request.path().trim();
        // 客户端侧预先校验 JSON 语法，给出明确错误提示
        try {
            JsonNode parsed = objectMapper.readTree(request.value());
            if (parsed == null) {
                throw BusinessException.badRequest("JSON 内容不能为空");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw BusinessException.badRequest("JSON 格式无效：" + ex.getMessage());
        }

        RespReply reply = executeOrThrow(instance, "JSON.SET", key, path, request.value());
        assertNotError(reply, "写入 JSON 失败");
        auditService.record("MULTIMODEL_JSON_SET", instance.getName() + "/" + key,
                "path=" + path + ",bytes=" + request.value().length(), true);
    }

    public long deleteJson(Long instanceId, String key, String path) {
        DbInstance instance = instanceService.require(instanceId);
        if (key == null || key.isBlank()) {
            throw BusinessException.badRequest("JSON 键名不能为空");
        }
        String k = key.trim();
        String effectivePath = (path == null || path.isBlank()) ? "$" : path.trim();
        RespReply reply = executeOrThrow(instance, "JSON.DEL", k, effectivePath);
        assertNotError(reply, "删除 JSON 失败");
        long removed = asLong(reply, 0L);
        auditService.record("MULTIMODEL_JSON_DEL", instance.getName() + "/" + k,
                "path=" + effectivePath + ",removed=" + removed, true);
        return removed;
    }

    public String numIncrJson(JsonNumIncrRequest request) {
        DbInstance instance = instanceService.require(request.instanceId());
        if (!Double.isFinite(request.delta())) {
            throw BusinessException.badRequest("增量值必须为有限实数");
        }
        String key = request.key().trim();
        String path = request.path().trim();
        RespReply reply = executeOrThrow(instance, "JSON.NUMINCRBY", key, path, String.valueOf(request.delta()));
        assertNotError(reply, "JSON 数值自增失败");
        String result = asString(reply);
        auditService.record("MULTIMODEL_JSON_NUMINCRBY", instance.getName() + "/" + key,
                "path=" + path + ",delta=" + request.delta(), true);
        return result;
    }

    public long arrAppendJson(JsonArrAppendRequest request) {
        DbInstance instance = instanceService.require(request.instanceId());
        String key = request.key().trim();
        String path = (request.path() == null || request.path().isBlank()) ? "$" : request.path().trim();
        RespReply reply = executeOrThrow(instance, "JSON.ARRAPPEND", key, path, request.value());
        assertNotError(reply, "JSON 数组追加失败");
        long len = extractFirstLong(reply);
        auditService.record("MULTIMODEL_JSON_ARRAPPEND", instance.getName() + "/" + key,
                "path=" + path + ",newLen=" + len, true);
        return len;
    }

    // ==================== Helpers ====================

    private RespReply executeOrThrow(DbInstance instance, String... args) {
        try {
            return gateway.execute(instance, args);
        } catch (IOException ex) {
            throw BusinessException.upstream("实例通信失败：" + ex.getMessage());
        }
    }

    private RespReply executeQuietly(DbInstance instance, String... args) {
        try {
            return gateway.execute(instance, args);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static boolean isUnknownCommand(RespReply reply) {
        if (!(reply instanceof RespReply.Error err) || err.message() == null) {
            return false;
        }
        String msg = err.message().toLowerCase(Locale.ROOT);
        return msg.contains("unknown command") || msg.contains("unsupported");
    }

    private static void assertNotError(RespReply reply, String prefix) {
        if (reply instanceof RespReply.Error err) {
            if (isUnknownCommand(reply)) {
                throw BusinessException.badRequest("目标实例不支持该多模型命令：" + err.message());
            }
            throw BusinessException.badRequest(prefix + "：" + err.message());
        }
    }

    private static String normalizeCollection(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_COLLECTION;
        }
        return raw.trim();
    }

    private static String normalizeAggregation(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_AGGREGATIONS.contains(upper)) {
            throw BusinessException.badRequest("不支持的聚合函数：" + raw + "（允许：AVG/SUM/MIN/MAX/COUNT/FIRST/LAST）");
        }
        return upper;
    }

    private static void validateFiniteNumbers(List<Double> values, String label) {
        for (int i = 0; i < values.size(); i++) {
            Double v = values.get(i);
            if (v == null || !Double.isFinite(v)) {
                throw BusinessException.badRequest(label + "第 " + (i + 1) + " 维必须为有限实数");
            }
        }
    }

    private static List<TimeSeriesPoint> parseTimeSeriesPoints(RespReply reply) {
        List<TimeSeriesPoint> points = new ArrayList<>();
        if (!(reply instanceof RespReply.Array arr) || arr.isNil()) {
            return points;
        }
        for (RespReply item : arr.items()) {
            if (item instanceof RespReply.Array pair && !pair.isNil() && pair.items().size() >= 2) {
                long ts = asLong(pair.items().get(0), Long.MIN_VALUE);
                double val = asDouble(pair.items().get(1), Double.NaN);
                if (ts != Long.MIN_VALUE && Double.isFinite(val)) {
                    points.add(new TimeSeriesPoint(ts, val));
                }
            }
        }
        return points;
    }

    private static String asString(RespReply reply) {
        if (reply == null) {
            return null;
        }
        return switch (reply) {
            case RespReply.Bulk b -> b.utf8();
            case RespReply.Simple s -> s.value();
            case RespReply.Integer i -> String.valueOf(i.value());
            default -> null;
        };
    }

    private static long asLong(RespReply reply, long fallback) {
        if (reply instanceof RespReply.Integer i) {
            return i.value();
        }
        String s = asString(reply);
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double asDouble(RespReply reply, double fallback) {
        if (reply instanceof RespReply.Integer i) {
            return i.value();
        }
        String s = asString(reply);
        if (s == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String extractFirstText(RespReply reply) {
        if (reply instanceof RespReply.Array arr && !arr.isNil() && !arr.items().isEmpty()) {
            return asString(arr.items().get(0));
        }
        return asString(reply);
    }

    private static long extractFirstLong(RespReply reply) {
        if (reply instanceof RespReply.Array arr && !arr.isNil() && !arr.items().isEmpty()) {
            return asLong(arr.items().get(0), 0L);
        }
        return asLong(reply, 0L);
    }

    private static List<String> extractObjectKeys(RespReply reply) {
        if (!(reply instanceof RespReply.Array arr) || arr.isNil() || arr.items().isEmpty()) {
            return List.of();
        }
        // JSONPath 模式下 JSON.OBJKEYS 返回嵌套数组 [[k1, k2]]，旧路径返回平铺 [k1, k2]
        RespReply first = arr.items().get(0);
        List<RespReply> source = (first instanceof RespReply.Array inner && !inner.isNil())
                ? inner.items()
                : arr.items();
        List<String> keys = new ArrayList<>(source.size());
        for (RespReply item : source) {
            String key = asString(item);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private String prettyPrintJson(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (RuntimeException ex) {
            return raw;
        }
    }
}
