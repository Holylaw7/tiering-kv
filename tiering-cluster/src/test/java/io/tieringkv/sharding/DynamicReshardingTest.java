package io.tieringkv.sharding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 动态重分片（ADR-0126）：路由版本、双写、切换、回滚、迁移。 */
class DynamicReshardingTest {

    @Test
    void routerRoutesDeterministically() {
        ShardRouter router = new ShardRouter(4);
        byte[] key = bytes("user:1");
        assertThat(router.route(key)).isEqualTo(router.route(key));
        assertThat(router.route(key)).isBetween(0, 3);
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 4, 16})
    void parameterizedShardCounts(int shards) {
        ShardRouter router = new ShardRouter(shards);
        assertThat(router.route(bytes("k"))).isBetween(0, shards - 1);
    }

    @Test
    void zeroShardsRejected() {
        assertThatThrownBy(() -> new ShardRouter(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void beginMigrationEntersDoubleWrite() {
        ShardRouter router = new ShardRouter(2);
        router.beginMigration(4);
        assertThat(router.migrating()).isTrue();
    }

    @Test
    void commitSwitchBumpsVersion() {
        ShardRouter router = new ShardRouter(2);
        long before = router.routingVersion();
        router.beginMigration(4);
        long version = router.commitSwitch(4);
        assertThat(version).isEqualTo(before + 1);
        assertThat(router.migrating()).isFalse();
        assertThat(router.shardCount()).isEqualTo(4);
    }

    @Test
    void rollbackRestoresOriginal() {
        ShardRouter router = new ShardRouter(2);
        router.beginMigration(4);
        long version = router.rollback(2);
        assertThat(router.shardCount()).isEqualTo(2);
        assertThat(router.migrating()).isFalse();
        assertThat(version).isEqualTo(router.routingVersion());
    }

    @Test
    void commitWithoutMigrationRejected() {
        ShardRouter router = new ShardRouter(2);
        assertThatThrownBy(() -> router.commitSwitch(4))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doubleBeginMigrationRejected() {
        ShardRouter router = new ShardRouter(2);
        router.beginMigration(4);
        assertThatThrownBy(() -> router.beginMigration(8))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void splitPlanGeneratesTargets() {
        ReshardPlanner planner = new ReshardPlanner();
        assertThat(planner.split(0, 3)).hasSize(3);
        assertThat(planner.split(0, 3).get(0).sourceShard())
                .isZero();
    }

    @Test
    void splitPlanTooFewRejected() {
        assertThatThrownBy(() -> new ReshardPlanner().split(0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergePlan() {
        ReshardPlanner.MergePlan plan =
                new ReshardPlanner().merge(1, 0);
        assertThat(plan.fromShard()).isEqualTo(1);
        assertThat(plan.toShard()).isZero();
    }

    @Test
    void mergeIntoSelfRejected() {
        assertThatThrownBy(() -> new ReshardPlanner().merge(1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shardMigrationMovesSelectedKeys() {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        source.put("a", bytes("1"));
        source.put("b", bytes("2"));
        ShardMigration migration = new ShardMigration(source, target);
        int moved = migration.migrate(key -> key.equals("a"));
        assertThat(moved).isEqualTo(1);
        assertThat(source).containsOnlyKeys("b");
        assertThat(target).containsOnlyKeys("a");
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 50, 200})
    void parameterizedMigrationVolume(int count) {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v" + i));
        }
        ShardMigration migration = new ShardMigration(source, target);
        int moved = migration.migrate(key -> key.endsWith("0"));
        assertThat(moved).isEqualTo((count + 9) / 10);
    }

    @Test
    void migrationVerify() {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        source.put("a", bytes("1"));
        source.put("b", bytes("2"));
        target.put("a", bytes("1"));
        ShardMigration migration = new ShardMigration(source, target);
        assertThat(migration.verify()).isTrue();
    }

    @Test
    void migrationVerifyDetectsMismatch() {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        source.put("a", bytes("1"));
        target.put("a", bytes("2"));
        assertThat(new ShardMigration(source, target).verify())
                .isFalse();
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {2, 4})
    void routerRouteStableAcrossKeys(int shards) {
        ShardRouter router = new ShardRouter(shards);
        for (int i = 0; i < 100; i++) {
            assertThat(router.route(bytes("k" + i)))
                    .isBetween(0, shards - 1);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
