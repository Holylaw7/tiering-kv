package io.tieringkv.platform;

import io.tieringkv.sharding.ReshardPlanner;
import io.tieringkv.sharding.ShardMigration;
import io.tieringkv.sharding.ShardRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 30 重分片边缘：路由/迁移/计划参数矩阵。 */
class Phase30ReshardEdgeTest {

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 3, 8, 32})
    void routerShardCounts(int shards) {
        ShardRouter router = new ShardRouter(shards);
        for (int i = 0; i < 100; i++) {
            assertThat(router.route(bytes("k" + i)))
                    .isBetween(0, shards - 1);
        }
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"", "a", "user:1", "中文"})
    void routerKeyBoundaries(String key) {
        ShardRouter router = new ShardRouter(4);
        assertThat(router.route(bytes(key))).isBetween(0, 3);
    }

    @Test
    void routerLongKeyBoundary() {
        ShardRouter router = new ShardRouter(4);
        assertThat(router.route(bytes("k".repeat(64))))
                .isBetween(0, 3);
    }

    @Test
    void routerVersionStartsZero() {
        assertThat(new ShardRouter(2).routingVersion()).isZero();
    }

    @ParameterizedTest(name = "targets {0}")
    @ValueSource(ints = {2, 4, 8})
    void splitPlanTargets(int targets) {
        ReshardPlanner planner = new ReshardPlanner();
        assertThat(planner.split(0, targets)).hasSize(targets);
    }

    @Test
    void mergePlanAcrossShards() {
        ReshardPlanner.MergePlan plan =
                new ReshardPlanner().merge(2, 0);
        assertThat(plan.toShard()).isZero();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {0, 10, 500})
    void migrationVolume(int count) {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v" + i));
        }
        ShardMigration migration = new ShardMigration(source, target);
        assertThat(migration.migrate(key -> true)).isEqualTo(count);
        assertThat(source).isEmpty();
        assertThat(target).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 100})
    void migrationPartialMove(int count) {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v" + i));
        }
        ShardMigration migration = new ShardMigration(source, target);
        migration.migrate(key -> key.endsWith("5"));
        assertThat(source.size() + target.size()).isEqualTo(count);
    }

    @Test
    void migrationVerifyEmptySource() {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        target.put("a", bytes("1"));
        assertThat(new ShardMigration(source, target).verify())
                .isTrue();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 20})
    void routerCommitRollbackCycles(int rounds) {
        ShardRouter router = new ShardRouter(2);
        for (int i = 0; i < rounds; i++) {
            router.beginMigration(4);
            if (i % 2 == 0) {
                router.commitSwitch(4);
            } else {
                router.rollback(2);
            }
        }
        assertThat(router.migrating()).isFalse();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
