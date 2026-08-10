package io.tieringkv.cluster.placement;

import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 自动均衡（ADR-0065）：检测/计划/epoch 保护/不自动执行数据搬迁。 */
class PlacementBalanceTest {

    @Test
    void balancedNoPlan() {
        BalanceScheduler scheduler = scheduler(regions(2, 0, 0), 0);
        BalancePlan plan = scheduler.evaluate();
        assertThat(plan.balanced()).isTrue();
        assertThat(plan.moves()).isEmpty();
    }

    @Test
    void regionCountImbalanceGeneratesMove() {
        // peer 分布失衡：n1 承载 4 副本，n2 承载 2 副本
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("b"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("b"), bytes("c"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n2");
        regions.createRegion(new RegionId(3), bytes("c"), bytes("d"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(4), bytes("d"), bytes("e"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        BalancePlan plan = scheduler(regions, 0).evaluate();
        assertThat(plan.balanced()).isFalse();
        assertThat(plan.moves()).isNotEmpty();
        assertThat(plan.moves().stream()
                .anyMatch(m -> m.reason().equals("region_count"))).isTrue();
    }

    @Test
    void moveTargetMustBePeer() {
        // region3 的 peers 只有 n1：无法迁移到 n2
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("b"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("b"), bytes("c"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(3), bytes("c"), bytes("d"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        BalancePlan plan = scheduler(regions, 0).evaluate();
        assertThat(plan.moves().stream()
                .noneMatch(m -> m.regionId().equals(new RegionId(3)))).isTrue();
    }

    @Test
    void leaderImbalanceGeneratesTransferPlan() {
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("b"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("b"), bytes("c"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(3), bytes("c"), bytes("d"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n2");
        BalancePlan plan = scheduler(regions, 0).evaluate();
        assertThat(plan.leaderSkew()).isGreaterThan(0);
        assertThat(plan.moves().stream()
                .anyMatch(m -> m.leaderMove()
                        && m.reason().equals("leader_balance"))).isTrue();
    }

    @Test
    void diskPressureGeneratesMove() {
        RegionManager regions = regions(2, 0, 0);
        BalanceScheduler scheduler = new BalanceScheduler(
                regions, 0, Map.of("n1", 10_000L, "n2", 1_000L),
                5_000L, Map.of(), 0, 10);
        BalancePlan plan = scheduler.evaluate();
        assertThat(plan.moves().stream()
                .anyMatch(m -> m.reason().equals("disk_pressure"))).isTrue();
    }

    @Test
    void cpuPressureGeneratesMove() {
        RegionManager regions = regions(2, 0, 0);
        BalanceScheduler scheduler = new BalanceScheduler(
                regions, 0, Map.of(), 0,
                Map.of("n1", 95, "n2", 20), 80, 10);
        BalancePlan plan = scheduler.evaluate();
        assertThat(plan.moves().stream()
                .anyMatch(m -> m.reason().equals("cpu_pressure"))).isTrue();
    }

    @Test
    void planDeduplicatesRegions() {
        RegionManager regions = regions(2, 0, 0);
        BalanceScheduler scheduler = new BalanceScheduler(
                regions, 0, Map.of("n1", 10_000L, "n2", 1_000L),
                5_000L, Map.of("n1", 95, "n2", 20), 80, 10);
        BalancePlan plan = scheduler.evaluate();
        long distinct = plan.moves().stream()
                .map(RegionMove::regionId).distinct().count();
        assertThat(distinct).isEqualTo(plan.moves().size());
    }

    @Test
    void planCarriesEpoch() {
        RegionManager regions = regions(2, 0, 0);
        BalancePlan plan = scheduler(regions, 0).evaluate();
        if (!plan.moves().isEmpty()) {
            assertThat(plan.moves().get(0).epoch()).isNotNull();
        }
    }

    @Test
    void executeLeaderMovesViaExecutor() {
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("b"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("b"), bytes("c"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(3), bytes("c"), bytes("d"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n2");
        BalancePlan plan = scheduler(regions, 0).evaluate();
        AtomicInteger calls = new AtomicInteger();
        int executed = scheduler(regions, 0).executeLeaderMoves(plan,
                (regionId, target) -> {
                    calls.incrementAndGet();
                    regions.transferLeader(regionId, target);
                    return true;
                });
        assertThat(executed).isGreaterThan(0);
        assertThat(calls.get()).isEqualTo(executed);
    }

    @Test
    void epochProtectionRejectsStalePlan() {
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        BalancePlan plan = scheduler(regions, 0).evaluate();
        // 计划生成后 region 分裂 → 纪元过期
        regions.splitRegion(new RegionId(1), bytes("m"));
        AtomicInteger calls = new AtomicInteger();
        int executed = scheduler(regions, 0).executeLeaderMoves(plan,
                (regionId, target) -> {
                    calls.incrementAndGet();
                    return true;
                });
        assertThat(executed).isZero();
        assertThat(calls.get()).isZero();
    }

    @Test
    void noAutoDataMoveExecution() {
        RegionManager regions = regions(2, 0, 0);
        BalancePlan plan = scheduler(regions, 0).evaluate();
        AtomicInteger calls = new AtomicInteger();
        scheduler(regions, 0).executeLeaderMoves(plan,
                (regionId, target) -> {
                    calls.incrementAndGet();
                    return true;
                });
        // 仅执行 leader 转移；region_count/disk/cpu 数据搬迁不自动执行
        long leaderMoves = plan.moves().stream()
                .filter(RegionMove::leaderMove).count();
        assertThat(calls.get()).isEqualTo(leaderMoves);
    }

    @Test
    void multipleOverloadedNodes() {
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("b"),
                List.of("n1", "n3"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("b"), bytes("c"),
                List.of("n1", "n3"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(3), bytes("c"), bytes("d"),
                List.of("n2", "n3"), RegionEpoch.INITIAL, "n2");
        regions.createRegion(new RegionId(4), bytes("d"), bytes("e"),
                List.of("n2", "n3"), RegionEpoch.INITIAL, "n2");
        regions.createRegion(new RegionId(5), bytes("e"), bytes("f"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n3");
        regions.createRegion(new RegionId(6), bytes("f"), bytes("g"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n3");
        BalancePlan plan = scheduler(regions, 0).evaluate();
        assertThat(plan.balanced()).isFalse();
    }

    @Test
    void emptyClusterBalanced() {
        BalancePlan plan = scheduler(new RegionManager(), 0).evaluate();
        assertThat(plan.balanced()).isTrue();
        assertThat(plan.moves()).isEmpty();
    }

    @Test
    void planLimitRespected() {
        RegionManager regions = new RegionManager();
        for (int i = 0; i < 10; i++) {
            regions.createRegion(new RegionId(i + 1),
                    bytes("k" + String.format("%02d", i)),
                    bytes("k" + String.format("%02d", i + 1)),
                    List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        }
        BalanceScheduler scheduler = new BalanceScheduler(
                regions, 0, Map.of(), 0, Map.of(), 0, 3);
        BalancePlan plan = scheduler.evaluate();
        assertThat(plan.moves().size()).isLessThanOrEqualTo(3);
    }

    @Test
    void diskAndCpuCombined() {
        RegionManager regions = regions(2, 0, 0);
        BalanceScheduler scheduler = new BalanceScheduler(
                regions, 0, Map.of("n1", 9_000L, "n2", 100L),
                5_000L, Map.of("n1", 95, "n2", 10), 80, 10);
        BalancePlan plan = scheduler.evaluate();
        assertThat(plan.moves().stream()
                .map(RegionMove::reason)
                .anyMatch(r -> r.equals("disk_pressure")
                        || r.equals("cpu_pressure"))).isTrue();
    }

    @Test
    void regionSkewReported() {
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("b"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("b"), bytes("c"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(3), bytes("c"), bytes("d"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        BalancePlan plan = scheduler(regions, 0).evaluate();
        assertThat(plan.regionSkew()).isGreaterThan(0);
    }

    private static BalanceScheduler scheduler(RegionManager regions, int maxSkew) {
        return new BalanceScheduler(regions, maxSkew,
                Map.of(), 0, Map.of(), 0, 10);
    }

    /** 每 region 副本 [n1,n2]，leader 交替。 */
    private static RegionManager regions(int count, int leaderShift, int ignored) {
        RegionManager regions = new RegionManager();
        for (int i = 0; i < count; i++) {
            String leader = i % 2 == 0 ? "n1" : "n2";
            regions.createRegion(new RegionId(i + 1),
                    bytes("k" + i), bytes("k" + (i + 1)),
                    List.of("n1", "n2"), RegionEpoch.INITIAL, leader);
        }
        return regions;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
