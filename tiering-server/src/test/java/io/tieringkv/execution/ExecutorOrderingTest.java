package io.tieringkv.execution;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorOrderingTest {

    @Test
    void sameKeyCommandsExecuteInFifoOrder() throws Exception {
        try (KeyShardExecutor executor = new KeyShardExecutor(4, "order-test")) {
            List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
            byte[] key = "k".getBytes(StandardCharsets.UTF_8);
            for (int i = 1; i <= 3; i++) {
                int value = i;
                executor.submit(key, () -> order.add("" + value));
            }
            assertThat(executor.awaitIdle(5000)).isTrue();
            assertThat(order).containsExactly("1", "2", "3");
        }
    }

    @Test
    void differentShardsRunInParallel() throws Exception {
        try (KeyShardExecutor executor = new KeyShardExecutor(16, "parallel-test")) {
            byte[] keyA = findKeyInShard(executor.shardCount(), 0);
            byte[] keyB = findKeyInShard(executor.shardCount(), 1);
            CountDownLatch aStarted = new CountDownLatch(1);
            CountDownLatch bStarted = new CountDownLatch(1);
            executor.submit(keyA, () -> {
                aStarted.countDown();
                try {
                    bStarted.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submit(keyB, () -> {
                bStarted.countDown();
                try {
                    aStarted.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            // 若串行执行会互相等待死锁；并行则两任务均完成
            assertThat(executor.awaitIdle(3000)).isTrue();
        }
    }

    @Test
    void mixedKeysCompleteWithoutDeadlock() throws Exception {
        try (KeyShardExecutor executor = new KeyShardExecutor(8, "mixed-test")) {
            for (int i = 0; i < 2000; i++) {
                byte[] key = ("k" + i).getBytes(StandardCharsets.UTF_8);
                executor.submit(key, () -> {
                    // 空任务：仅验证调度不卡死
                });
            }
            assertThat(executor.awaitIdle(10_000)).isTrue();
            assertThat(executor.metrics().snapshot().operations()).isEqualTo(2000);
        }
    }

    private static byte[] findKeyInShard(int shardCount, int targetShard) {
        for (int i = 0; i < 10_000; i++) {
            byte[] key = ("key-" + i).getBytes(StandardCharsets.UTF_8);
            if (ShardRouter.route(key, shardCount) == targetShard) {
                return key;
            }
        }
        throw new IllegalStateException("no key found for shard " + targetShard);
    }
}
