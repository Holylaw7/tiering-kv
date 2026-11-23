package io.tieringkv.cluster.routing;

import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 统一路由（ADR-0066）：key/slot/epoch/缓存/更新。 */
class UnifiedRoutingTest {

    private RoutingTable table;
    private RoutingCache cache;
    private RouteEpochGuard guard;

    @BeforeEach
    void setUp() {
        table = new RoutingTable();
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                RegionEpoch.INITIAL, "n1", "g1", false));
        table.update(entry(new RegionId(2), "m", "z", 8192, 16383,
                RegionEpoch.INITIAL, "n2", "g2", false));
        cache = new RoutingCache(table);
        guard = new RouteEpochGuard(table, cache);
    }

    @Test
    void keyLookupRoutesToRegion() {
        assertThat(table.route(bytes("abc")).regionId()).isEqualTo(new RegionId(1));
        assertThat(table.route(bytes("xyz")).regionId()).isEqualTo(new RegionId(2));
    }

    @Test
    void slotLookupRoutesToRegion() {
        assertThat(table.routeSlot(0).regionId()).isEqualTo(new RegionId(1));
        assertThat(table.routeSlot(8192).regionId()).isEqualTo(new RegionId(2));
        assertThat(table.routeSlot(16383).regionId()).isEqualTo(new RegionId(2));
    }

    @Test
    void keyAtStartBoundary() {
        assertThat(table.route(bytes("a")).regionId()).isEqualTo(new RegionId(1));
    }

    @Test
    void keyAtEndExclusive() {
        // endKey 不包含：m 属于 r2
        assertThat(table.route(bytes("m")).regionId()).isEqualTo(new RegionId(2));
    }

    @Test
    void slotBoundaries() {
        assertThat(table.routeSlot(8191).regionId()).isEqualTo(new RegionId(1));
        assertThat(table.routeSlot(8192).regionId()).isEqualTo(new RegionId(2));
    }

    @Test
    void regionToRaftGroupMapping() {
        assertThat(table.raftGroupFor(new RegionId(1))).isEqualTo("g1");
        assertThat(table.raftGroupFor(new RegionId(2))).isEqualTo("g2");
    }

    @Test
    void updateReplacesEntry() {
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                new RegionEpoch(2, 1), "n3", "g1x", false));
        assertThat(table.route(bytes("abc")).leader()).isEqualTo("n3");
        assertThat(table.raftGroupFor(new RegionId(1))).isEqualTo("g1x");
    }

    @Test
    void versionIncrementsOnUpdate() {
        long v0 = table.version();
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                new RegionEpoch(2, 1), "n1", "g1", false));
        assertThat(table.version()).isEqualTo(v0 + 1);
    }

    @Test
    void epochMismatchDetected() {
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                new RegionEpoch(2, 1), "n1", "g1", false));
        RouteEpochGuard.GuardedRoute result = guard.check(
                bytes("abc"), RegionEpoch.INITIAL);
        assertThat(result.fresh()).isFalse();
    }

    @Test
    void guardAcceptsCurrentEpoch() {
        RouteEpochGuard.GuardedRoute result = guard.check(
                bytes("abc"), RegionEpoch.INITIAL);
        assertThat(result.fresh()).isTrue();
        assertThat(result.entry().regionId()).isEqualTo(new RegionId(1));
    }

    @Test
    void staleCacheRefreshes() {
        RoutingTableEntry first = cache.route(bytes("abc"));
        assertThat(first.leader()).isEqualTo("n1");
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                new RegionEpoch(2, 1), "n9", "g1", false));
        RoutingTableEntry refreshed = cache.route(bytes("abc"));
        assertThat(refreshed.leader()).isEqualTo("n9");
    }

    @Test
    void cacheMissPopulates() {
        assertThat(cache.size()).isZero();
        cache.route(bytes("abc"));
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void cacheInvalidate() {
        cache.route(bytes("abc"));
        cache.invalidate(bytes("abc"));
        assertThat(cache.size()).isZero();
    }

    @Test
    void cacheVersionChangeRefresh() {
        cache.route(bytes("abc"));
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                new RegionEpoch(2, 1), "n1", "g1", false));
        assertThat(cache.route(bytes("abc")).epoch().confVer()).isEqualTo(2);
    }

    @Test
    void unknownKeyThrows() {
        assertThatThrownBy(() -> table.route(bytes("0")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownSlotThrows() {
        RoutingTable empty = new RoutingTable();
        assertThatThrownBy(() -> empty.routeSlot(5))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void migratingFlagSurfacesAsk() {
        RoutingTableEntry entry = table.route(bytes("abc"))
                .withMigrating(true);
        table.update(entry);
        assertThat(table.route(bytes("abc")).migrating()).isTrue();
    }

    @Test
    void missingLeaderSurfacesTryAgain() {
        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                new RegionEpoch(2, 1), null, "g1", false));
        assertThat(table.route(bytes("abc")).leader()).isNull();
    }

    @Test
    void concurrentUpdatesConsistent() throws Exception {
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            final int writer = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        table.update(entry(new RegionId(1), "a", "m", 0, 8191,
                                new RegionEpoch(2 + i, 1),
                                "n" + writer, "g1", false));
                        table.route(bytes("abc"));
                    }
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(table.route(bytes("abc"))).isNotNull();
    }

    @Test
    void slotRangeCoversAllSlots() {
        assertThat(table.routeSlot(0).regionId()).isEqualTo(new RegionId(1));
        assertThat(table.routeSlot(16383).regionId()).isEqualTo(new RegionId(2));
    }

    @Test
    void routeAfterSplit() {
        table.update(entry(new RegionId(11), "a", "g", 0, 4095,
                new RegionEpoch(2, 1), "n1", "g1a", false));
        table.update(entry(new RegionId(12), "g", "m", 4096, 8191,
                new RegionEpoch(2, 1), "n1", "g1b", false));
        assertThat(table.route(bytes("b")).regionId()).isEqualTo(new RegionId(11));
        assertThat(table.route(bytes("h")).regionId()).isEqualTo(new RegionId(12));
        assertThat(table.route(bytes("n")).regionId()).isEqualTo(new RegionId(2));
    }

    @Test
    void withLeaderAdvancesEpoch() {
        RoutingTableEntry changed = table.route(bytes("abc"))
                .withLeader("n7");
        assertThat(changed.epoch().confVer()).isEqualTo(2);
        assertThat(changed.leader()).isEqualTo("n7");
    }

    @Test
    void invalidSlotRangeRejected() {
        assertThatThrownBy(() -> new RoutingTableEntry(new RegionId(1),
                bytes("a"), bytes("m"), -1, 5,
                RegionEpoch.INITIAL, "n1", "g1", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RoutingTableEntry entry(RegionId id, String start, String end,
                                           int slotStart, int slotEnd,
                                           RegionEpoch epoch, String leader,
                                           String group, boolean migrating) {
        return new RoutingTableEntry(id, bytes(start),
                end == null ? null : bytes(end), slotStart, slotEnd,
                epoch, leader, group, migrating);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
