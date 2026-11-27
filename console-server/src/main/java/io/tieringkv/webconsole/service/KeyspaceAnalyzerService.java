package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespClient;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.support.Text;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 键空间大键 / 命名空间前缀 / TTL 健康度只读诊断分析器（v6.4.0 D2）。
 *
 * <p>基于有界 {@code SCAN} 游标采样与 100 键批量 RESP Pipeline（一次 RTT 打包
 * {@code TYPE}/{@code TTL} 与按类型的尺寸命令 {@code STRLEN}/{@code HLEN}/{@code LLEN}/
 * {@code SCARD}/{@code ZCARD}/{@code XLEN}/{@code JSON.STRLEN}），配合并发限流与时间预算，
 * 对在线业务实例与控制面内存均保持有界可控。
 */
@Service
public class KeyspaceAnalyzerService {

    static final int DEFAULT_MAX_KEYS = 2_000;
    static final int MIN_MAX_KEYS = 100;
    static final int MAX_ALLOWED_KEYS = 10_000;
    static final int DEFAULT_TOP_N = 20;
    static final int MAX_TOP_N = 100;
    static final int MAX_PREFIXES = 15;
    static final int SCAN_COUNT = 200;
    static final int PIPELINE_BATCH = 100;
    static final int JSON_FALLBACK_BATCH = 10;
    static final int MAX_CONCURRENT_ANALYSES = 2;
    static final long DEFAULT_TIME_BUDGET_MS = 15_000L;
    static final long BIG_STRING_WARN_BYTES = 65_536L;
    static final long BIG_COLLECTION_WARN_ITEMS = 1_000L;

    private final InstanceService instanceService;
    private final KeyspaceService keyspaceService;
    private final RespGateway gateway;
    private final Semaphore concurrencyLimiter;
    private final long timeBudgetMs;

    @Autowired
    public KeyspaceAnalyzerService(InstanceService instanceService,
                                   KeyspaceService keyspaceService,
                                   RespGateway gateway) {
        this(instanceService, keyspaceService, gateway, MAX_CONCURRENT_ANALYSES, DEFAULT_TIME_BUDGET_MS);
    }

    KeyspaceAnalyzerService(InstanceService instanceService,
                            KeyspaceService keyspaceService,
                            RespGateway gateway,
                            int maxConcurrent,
                            long timeBudgetMs) {
        this.instanceService = instanceService;
        this.keyspaceService = keyspaceService;
        this.gateway = gateway;
        this.concurrencyLimiter = new Semaphore(Math.max(1, maxConcurrent));
        this.timeBudgetMs = Math.max(1L, timeBudgetMs);
    }

    /** 诊断请求参数（全字段可选，缺失自动使用安全默认值）。 */
    public record AnalyzeRequest(
            String pattern,
            Integer maxKeys,
            String delimiter,
            Integer topN) {
    }

    /** 命名空间前缀聚合条目。 */
    public record PrefixStat(
            String prefix,
            long keyCount,
            long totalSizeUnits,
            double sharePercent) {
    }

    /** 大键（BigKey）榜单条目。 */
    public record BigKeyEntry(
            String key,
            String type,
            long sizeUnits,
            String unitLabel,
            long ttl) {
    }

    /** TTL 健康度分桶统计。 */
    public record TtlDistribution(
            long noExpire,
            long under1h,
            long between1hAnd24h,
            long over24h) {
    }

    /** 键空间只读诊断结果视图。 */
    public record KeyspaceAnalysisView(
            Long instanceId,
            String pattern,
            String delimiter,
            long scannedKeys,
            long totalDbSize,
            boolean truncated,
            long durationMs,
            Map<String, Long> typeDistribution,
            TtlDistribution ttlDistribution,
            List<PrefixStat> topPrefixes,
            List<BigKeyEntry> bigKeys,
            List<String> recommendations) {
        public KeyspaceAnalysisView {
            typeDistribution = typeDistribution == null ? Map.of() : Map.copyOf(typeDistribution);
            topPrefixes = topPrefixes == null ? List.of() : List.copyOf(topPrefixes);
            bigKeys = bigKeys == null ? List.of() : List.copyOf(bigKeys);
            recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        }
    }

    public KeyspaceAnalysisView analyze(Long instanceId, AnalyzeRequest request) {
        DbInstance instance = instanceService.require(instanceId);
        if (!concurrencyLimiter.tryAcquire()) {
            throw BusinessException.tooManyRequests("已有键空间诊断任务正在执行，请稍后再试");
        }
        try {
            return runAnalyze(instanceId, instance, request);
        } finally {
            concurrencyLimiter.release();
        }
    }

    private KeyspaceAnalysisView runAnalyze(Long instanceId, DbInstance instance, AnalyzeRequest request) {
        String pattern = request != null && request.pattern() != null && !request.pattern().isBlank()
                ? request.pattern().trim() : "*";
        int maxKeys = clamp(request != null ? request.maxKeys() : null,
                DEFAULT_MAX_KEYS, MIN_MAX_KEYS, MAX_ALLOWED_KEYS);
        String delimiter = request != null && request.delimiter() != null && !request.delimiter().isEmpty()
                ? request.delimiter() : ":";
        int topN = clamp(request != null ? request.topN() : null,
                DEFAULT_TOP_N, 5, MAX_TOP_N);

        long startedAt = System.nanoTime();
        long deadlineNanos = startedAt + TimeUnit.MILLISECONDS.toNanos(timeBudgetMs);
        long dbSize = queryDbSizeQuietly(instance);
        Set<String> sampledKeys = new LinkedHashSet<>();
        boolean truncated = false;
        AtomicBoolean timedOut = new AtomicBoolean(false);
        String cursor = "0";
        try {
            do {
                if (System.nanoTime() >= deadlineNanos) {
                    truncated = true;
                    timedOut.set(true);
                    break;
                }
                RespReply reply = gateway.execute(instance, "SCAN", cursor,
                        "MATCH", pattern, "COUNT", String.valueOf(SCAN_COUNT));
                if (reply instanceof RespReply.Error error) {
                    throw BusinessException.badRequest("SCAN 诊断失败：" + error.message());
                }
                if (!(reply instanceof RespReply.Array array) || array.isNil() || array.items().size() < 2) {
                    throw BusinessException.upstream("SCAN 返回结构异常");
                }
                cursor = bulkText(array.items().get(0));
                if (cursor == null) {
                    cursor = "0";
                }
                if (array.items().get(1) instanceof RespReply.Array keyArray && !keyArray.isNil()) {
                    for (RespReply item : keyArray.items()) {
                        String key = bulkText(item);
                        if (key != null) {
                            sampledKeys.add(key);
                            if (sampledKeys.size() >= maxKeys) {
                                truncated = !"0".equals(cursor);
                                break;
                            }
                        }
                    }
                }
            } while (!"0".equals(cursor) && sampledKeys.size() < maxKeys);
        } catch (IOException ex) {
            throw BusinessException.upstream("键空间诊断扫描失败：" + ex.getMessage());
        }

        List<String> keysList = new ArrayList<>(sampledKeys);
        Map<String, Long> typeCounts = new LinkedHashMap<>();
        Map<String, PrefixAccumulator> prefixMap = new LinkedHashMap<>();
        List<BigKeyEntry> allCandidates = new ArrayList<>(keysList.size());
        long noExpire = 0L;
        long under1h = 0L;
        long between1hAnd24h = 0L;
        long over24h = 0L;
        long scannedLive = 0L;

        for (int offset = 0; offset < keysList.size(); offset += PIPELINE_BATCH) {
            if (System.nanoTime() >= deadlineNanos) {
                truncated = true;
                timedOut.set(true);
                break;
            }
            int end = Math.min(offset + PIPELINE_BATCH, keysList.size());
            List<String> batch = keysList.subList(offset, end);
            List<KeyEntry> metas = keyspaceService.inspectBatch(instance, batch);
            long[] sizes = probeSizesBatch(instance, metas, deadlineNanos, timedOut);
            if (timedOut.get()) {
                truncated = true;
            }
            for (int i = 0; i < metas.size(); i++) {
                KeyEntry meta = metas.get(i);
                String type = meta.type() == null ? "none" : meta.type().toLowerCase(Locale.ROOT);
                if ("none".equals(type)) {
                    continue;
                }
                scannedLive++;
                long size = i < sizes.length ? sizes[i] : 0L;
                typeCounts.merge(type, 1L, Long::sum);

                long ttl = meta.ttl();
                if (ttl < 0L) {
                    noExpire++;
                } else if (ttl < 3_600L) {
                    under1h++;
                } else if (ttl < 86_400L) {
                    between1hAnd24h++;
                } else {
                    over24h++;
                }

                String prefix = extractPrefix(meta.key(), delimiter);
                PrefixAccumulator acc = prefixMap.computeIfAbsent(prefix, k -> new PrefixAccumulator());
                acc.keyCount++;
                acc.totalSizeUnits += size;

                allCandidates.add(new BigKeyEntry(meta.key(), type, size, unitLabelFor(type), ttl));
            }
        }

        allCandidates.sort(Comparator.comparingLong(BigKeyEntry::sizeUnits).reversed()
                .thenComparing(BigKeyEntry::key));
        List<BigKeyEntry> bigKeys = allCandidates.size() > topN
                ? new ArrayList<>(allCandidates.subList(0, topN))
                : allCandidates;

        long totalForRatio = Math.max(1L, scannedLive);
        List<PrefixStat> topPrefixes = prefixMap.entrySet().stream()
                .sorted((a, b) -> {
                    int byCount = Long.compare(b.getValue().keyCount, a.getValue().keyCount);
                    if (byCount != 0) {
                        return byCount;
                    }
                    return Long.compare(b.getValue().totalSizeUnits, a.getValue().totalSizeUnits);
                })
                .limit(MAX_PREFIXES)
                .map(e -> new PrefixStat(
                        e.getKey(),
                        e.getValue().keyCount,
                        e.getValue().totalSizeUnits,
                        round2((e.getValue().keyCount * 100.0d) / totalForRatio)))
                .toList();

        TtlDistribution ttlDist = new TtlDistribution(noExpire, under1h, between1hAnd24h, over24h);
        List<String> recommendations = new ArrayList<>(buildRecommendations(scannedLive, ttlDist, topPrefixes, bigKeys));
        if (timedOut.get()) {
            recommendations.add("诊断已触及单次时间预算（" + timeBudgetMs + " ms）并提前截断，建议缩小匹配前缀或降低采样上限后复检。");
        }
        long durationMs = Math.max(1L, (System.nanoTime() - startedAt) / 1_000_000L);
        long effectiveDbSize = dbSize >= 0L ? dbSize : scannedLive;

        return new KeyspaceAnalysisView(
                instanceId, pattern, delimiter, scannedLive, effectiveDbSize, truncated,
                durationMs, typeCounts, ttlDist, topPrefixes, bigKeys, recommendations);
    }

    private long[] probeSizesBatch(DbInstance instance, List<KeyEntry> metas,
                                   long deadlineNanos, AtomicBoolean timedOut) {
        long[] sizes = new long[metas.size()];
        List<List<byte[]>> commands = new ArrayList<>();
        List<Integer> slotIndices = new ArrayList<>();
        for (int i = 0; i < metas.size(); i++) {
            KeyEntry meta = metas.get(i);
            String type = meta.type() == null ? "none" : meta.type().toLowerCase(Locale.ROOT);
            List<byte[]> cmd = sizeCommandFor(meta.key(), type);
            if (cmd != null) {
                commands.add(cmd);
                slotIndices.add(i);
            }
        }
        if (commands.isEmpty()) {
            return sizes;
        }
        List<Integer> jsonFallbackSlots = new ArrayList<>();
        try {
            List<RespReply> replies = gateway.executeBatch(instance, commands);
            int limit = Math.min(replies.size(), slotIndices.size());
            for (int i = 0; i < limit; i++) {
                RespReply reply = replies.get(i);
                int slot = slotIndices.get(i);
                long parsed = extractSize(reply);
                if (parsed >= 0L) {
                    sizes[slot] = parsed;
                } else if (reply instanceof RespReply.Error
                        && "json".equalsIgnoreCase(metas.get(slot).type())) {
                    jsonFallbackSlots.add(slot);
                }
            }
            if (!jsonFallbackSlots.isEmpty()) {
                probeJsonFallback(instance, metas, jsonFallbackSlots, sizes, deadlineNanos, timedOut);
            }
        } catch (IOException ex) {
            throw BusinessException.upstream("键空间批量尺寸探测失败：" + ex.getMessage());
        }
        return sizes;
    }

    /** 仅在内核不支持 JSON.STRLEN 时，以 10 键小批次降级探测 JSON 大小，防止 100 键大 JSON 同时打爆堆内存。 */
    private void probeJsonFallback(DbInstance instance, List<KeyEntry> metas,
                                   List<Integer> slots, long[] sizes,
                                   long deadlineNanos, AtomicBoolean timedOut) throws IOException {
        for (int offset = 0; offset < slots.size(); offset += JSON_FALLBACK_BATCH) {
            if (System.nanoTime() >= deadlineNanos) {
                timedOut.set(true);
                break;
            }
            int end = Math.min(offset + JSON_FALLBACK_BATCH, slots.size());
            List<Integer> chunk = slots.subList(offset, end);
            List<List<byte[]>> fallbackCmds = new ArrayList<>(chunk.size());
            for (int slot : chunk) {
                fallbackCmds.add(RespClient.commandBytes("JSON.GET", metas.get(slot).key(), "$"));
            }
            List<RespReply> replies = gateway.executeBatch(instance, fallbackCmds);
            int limit = Math.min(replies.size(), chunk.size());
            for (int i = 0; i < limit; i++) {
                long parsed = extractSize(replies.get(i));
                if (parsed >= 0L) {
                    sizes[chunk.get(i)] = parsed;
                }
            }
        }
    }

    private static long extractSize(RespReply reply) {
        if (reply instanceof RespReply.Integer integer) {
            return Math.max(0L, integer.value());
        }
        if (reply instanceof RespReply.Bulk bulk && bulk.value() != null) {
            return bulk.value().length;
        }
        if (reply instanceof RespReply.Array array && !array.isNil()) {
            long sum = 0L;
            boolean any = false;
            for (RespReply item : array.items()) {
                if (item instanceof RespReply.Integer integer) {
                    sum += Math.max(0L, integer.value());
                    any = true;
                }
            }
            return any ? sum : -1L;
        }
        return -1L;
    }

    private static List<byte[]> sizeCommandFor(String key, String type) {
        return switch (type) {
            case "string" -> RespClient.commandBytes("STRLEN", key);
            case "hash" -> RespClient.commandBytes("HLEN", key);
            case "list" -> RespClient.commandBytes("LLEN", key);
            case "set" -> RespClient.commandBytes("SCARD", key);
            case "zset" -> RespClient.commandBytes("ZCARD", key);
            case "stream" -> RespClient.commandBytes("XLEN", key);
            case "json" -> RespClient.commandBytes("JSON.STRLEN", key, "$");
            default -> null;
        };
    }

    static String unitLabelFor(String type) {
        return switch (type) {
            case "string", "json" -> "bytes";
            case "hash" -> "fields";
            case "list" -> "items";
            case "set", "zset" -> "members";
            case "stream" -> "entries";
            default -> "units";
        };
    }

    static String extractPrefix(String key, String delimiter) {
        if (key == null || key.isEmpty()) {
            return "(empty)";
        }
        int idx = key.indexOf(delimiter);
        if (idx <= 0) {
            return "(root)";
        }
        return key.substring(0, idx + delimiter.length()) + "*";
    }

    static List<String> buildRecommendations(long scannedKeys,
                                             TtlDistribution ttl,
                                             List<PrefixStat> topPrefixes,
                                             List<BigKeyEntry> bigKeys) {
        List<String> tips = new ArrayList<>();
        if (scannedKeys == 0L) {
            tips.add("当前匹配模式下未扫描到存活键。");
            return tips;
        }
        double noExpirePct = (ttl.noExpire() * 100.0d) / scannedKeys;
        if (scannedKeys >= 20 && noExpirePct >= 70.0d) {
            tips.add(String.format(Locale.ROOT,
                    "永久未设置 TTL 的键占比达 %.1f%%（%d/%d），建议为缓存类业务前缀配置合理过期时间以降低热层水位压力。",
                    noExpirePct, ttl.noExpire(), scannedKeys));
        }
        long largeStrings = bigKeys.stream()
                .filter(k -> ("string".equals(k.type()) || "json".equals(k.type()))
                        && k.sizeUnits() >= BIG_STRING_WARN_BYTES)
                .count();
        if (largeStrings > 0) {
            tips.add("检出 " + largeStrings + " 个单值 ≥ 64 KB 的大字符串/JSON 键，建议评估压缩或拆分为 Hash 分片存储。");
        }
        long largeCollections = bigKeys.stream()
                .filter(k -> !("string".equals(k.type()) || "json".equals(k.type()))
                        && k.sizeUnits() >= BIG_COLLECTION_WARN_ITEMS)
                .count();
        if (largeCollections > 0) {
            tips.add("检出 " + largeCollections + " 个元素基数 ≥ 1,000 的大集合键，建议拆分命名空间并避免全量阻塞命令。");
        }
        if (scannedKeys >= 50 && !topPrefixes.isEmpty() && topPrefixes.get(0).sharePercent() >= 60.0d) {
            PrefixStat dominant = topPrefixes.get(0);
            tips.add(String.format(Locale.ROOT,
                    "前缀 %s 占当前采样键数的 %.1f%%，建议为其单独设立容量告警规则与过期策略。",
                    dominant.prefix(), dominant.sharePercent()));
        }
        if (tips.isEmpty()) {
            tips.add("当前采样键空间分布均衡，未发现明显大键或 TTL 失衡风险。");
        }
        return tips;
    }

    private long queryDbSizeQuietly(DbInstance instance) {
        try {
            RespReply reply = gateway.execute(instance, "DBSIZE");
            if (reply instanceof RespReply.Integer integer) {
                return integer.value();
            }
        } catch (IOException | RuntimeException ignored) {
            // 降级为采样数
        }
        return -1L;
    }

    private static String bulkText(RespReply reply) {
        return reply instanceof RespReply.Bulk bulk ? Text.decode(bulk.value()) : null;
    }

    private static int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private static final class PrefixAccumulator {
        private long keyCount;
        private long totalSizeUnits;
    }
}
