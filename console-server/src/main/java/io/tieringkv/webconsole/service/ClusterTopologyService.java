package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.support.InfoParser;
import io.tieringkv.webconsole.web.dto.ClusterDtos.ClusterTopologyView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.PersistenceStatusView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.RegionNodeView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.TransactionMetricsView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 分布式集群拓扑与 16384 HashSlot 分布解析服务（v6.2.0 C2）：
 * 解析数据面 {@code INFO} 中的 {@code # Cluster}、{@code # Transaction} 与 {@code # Persistence} 段落；
 * 单机实例自动呈现为 1 Region / 16384 槽位的 {@code STANDALONE} 视图。
 */
@Service
public class ClusterTopologyService {

    static final int DEFAULT_TOTAL_SLOTS = 16384;

    private final InstanceService instanceService;
    private final RespGateway gateway;

    public ClusterTopologyService(InstanceService instanceService, RespGateway gateway) {
        this.instanceService = instanceService;
        this.gateway = gateway;
    }

    public ClusterTopologyView inspect(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        String rawInfo = gateway.info(instance);
        return parseTopology(instance, rawInfo);
    }

    ClusterTopologyView parseTopology(DbInstance instance, String rawInfo) {
        Map<String, Map<String, String>> sections = InfoParser.parse(rawInfo);
        String version = firstNonBlank(
                InfoParser.get(sections, "Server", "tieringkv_version"),
                InfoParser.get(sections, "Server", "redis_version"),
                instance.getVersion(),
                "unknown");
        long totalKeys = Math.max(
                InfoParser.keyCount(sections),
                InfoParser.longValue(sections, "Stats", "total_keys", 0L));

        Map<String, String> clusterSec = sections.get("Cluster");
        boolean distributed = clusterSec != null
                && ("1".equals(clusterSec.get("cluster_enabled"))
                || "distributed".equalsIgnoreCase(clusterSec.get("cluster_mode")));

        int totalSlots = (int) InfoParser.longValue(sections, "Cluster", "cluster_slots_total", DEFAULT_TOTAL_SLOTS);
        if (totalSlots <= 0) {
            totalSlots = DEFAULT_TOTAL_SLOTS;
        }

        List<RawRegion> parsedRegions = distributed ? extractRegions(clusterSec) : List.of();
        List<RegionNodeView> regions;
        String mode;
        String nodeId;
        if (distributed && !parsedRegions.isEmpty()) {
            mode = "DISTRIBUTED";
            nodeId = firstNonBlank(clusterSec.get("cluster_node_id"), instance.getName());
            regions = allocateSlots(parsedRegions, totalSlots);
        } else {
            mode = "STANDALONE";
            nodeId = firstNonBlank(InfoParser.get(sections, "Server", "run_id"), instance.getName());
            regions = List.of(new RegionNodeView(
                    0, "standalone", instance.getHost(), instance.getPort(), totalSlots, 100.0));
        }

        TransactionMetricsView txn = sections.containsKey("Transaction")
                ? parseTransaction(sections)
                : null;
        PersistenceStatusView persistence = parsePersistence(sections);

        return new ClusterTopologyView(
                instance.getId(),
                instance.getName(),
                mode,
                distributed && !parsedRegions.isEmpty(),
                nodeId,
                version,
                totalKeys,
                totalSlots,
                regions.size(),
                regions,
                txn,
                persistence);
    }

    private record RawRegion(int index, String regionId, String host, int port) {
    }

    private static List<RawRegion> extractRegions(Map<String, String> clusterSec) {
        List<RawRegion> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : clusterSec.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("region_")) {
                continue;
            }
            int idx;
            try {
                idx = Integer.parseInt(key.substring("region_".length()));
            } catch (NumberFormatException ex) {
                continue;
            }
            String val = entry.getValue();
            if (val == null || !val.contains("@")) {
                continue;
            }
            String[] idAndAddr = val.split("@", 2);
            String regionId = idAndAddr[0].trim();
            String addr = idAndAddr[1].trim();
            String host = addr;
            int port = 7100;
            int colon = addr.lastIndexOf(':');
            if (colon > 0 && colon < addr.length() - 1) {
                host = addr.substring(0, colon);
                try {
                    port = Integer.parseInt(addr.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    port = 7100;
                }
            }
            list.add(new RawRegion(idx, regionId, host, port));
        }
        list.sort(Comparator.comparingInt(RawRegion::index));
        return list;
    }

    /** 按 CoordinatorRuntime 的 HashSlotRouter.slot(key) % N 口径精确计算各 Region 槽位数。 */
    static List<RegionNodeView> allocateSlots(List<RawRegion> rawRegions, int totalSlots) {
        int n = rawRegions.size();
        int base = totalSlots / n;
        int remainder = totalSlots % n;
        List<RegionNodeView> views = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            RawRegion r = rawRegions.get(i);
            int slots = base + (i < remainder ? 1 : 0);
            double pct = Math.round((slots * 10_000.0) / totalSlots) / 100.0;
            views.add(new RegionNodeView(r.index(), r.regionId(), r.host(), r.port(), slots, pct));
        }
        return views;
    }

    private static TransactionMetricsView parseTransaction(Map<String, Map<String, String>> sections) {
        return new TransactionMetricsView(
                InfoParser.longValue(sections, "Transaction", "begin_txn", 0L),
                InfoParser.longValue(sections, "Transaction", "active_txn", 0L),
                InfoParser.longValue(sections, "Transaction", "committed_txn", 0L),
                InfoParser.longValue(sections, "Transaction", "rollback_txn", 0L),
                InfoParser.longValue(sections, "Transaction", "conflict_txn", 0L),
                InfoParser.longValue(sections, "Transaction", "abort_txn", 0L),
                InfoParser.longValue(sections, "Transaction", "recovery_txn", 0L),
                InfoParser.longValue(sections, "Transaction", "lock_count", 0L),
                InfoParser.doubleValue(sections, "Transaction", "txn_commit_latency_ms", 0.0),
                InfoParser.doubleValue(sections, "Transaction", "txn_prepare_latency_ms", 0.0),
                InfoParser.longValue(sections, "Transaction", "txn_network_retry", 0L),
                InfoParser.doubleValue(sections, "Transaction", "txn_lock_wait_ms", 0.0),
                InfoParser.longValue(sections, "Transaction", "txn_region_count", 0L));
    }

    private static PersistenceStatusView parsePersistence(Map<String, Map<String, String>> sections) {
        if (!sections.containsKey("Persistence")) {
            return null;
        }
        return new PersistenceStatusView(
                InfoParser.longValue(sections, "Persistence", "rdb_bgsave_in_progress", 0L) == 1L,
                InfoParser.longValue(sections, "Persistence", "rdb_last_save_time", 0L),
                firstNonBlank(InfoParser.get(sections, "Persistence", "rdb_last_bgsave_status"), "ok"),
                InfoParser.longValue(sections, "Persistence", "rdb_last_bgsave_duration_ms", 0L),
                InfoParser.get(sections, "Persistence", "rdb_last_bgsave_error"));
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }
}
