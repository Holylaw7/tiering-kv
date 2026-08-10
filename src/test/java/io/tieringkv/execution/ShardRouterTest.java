package io.tieringkv.execution;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ShardRouterTest {

    @Test
    void sameKeyRoutesToSameShard() {
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 100; i++) {
            assertThat(ShardRouter.route(key, 16)).isEqualTo(ShardRouter.route(key, 16));
        }
    }

    @Test
    void routesWithinRange() {
        for (int shards : new int[]{1, 4, 16, 256}) {
            for (int i = 0; i < 1000; i++) {
                int shard = ShardRouter.route(("k" + i).getBytes(StandardCharsets.UTF_8), shards);
                assertThat(shard).isBetween(0, shards - 1);
            }
        }
    }

    @Test
    void distributesAcrossShards() {
        int shards = 16;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(ShardRouter.route(("key-" + i).getBytes(StandardCharsets.UTF_8), shards));
        }
        assertThat(seen).hasSizeGreaterThan(8);
    }
}
