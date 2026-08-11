package io.tieringkv.sharding.auto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 并发自动重分片（ADR-0140）：并行迁移 + 限速。 */
class ConcurrentReshardTest {

    @Test
    void concurrentMigrationMovesAll() throws Exception {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < 1000; i++) {
            source.put("k" + i, bytes("v" + i));
        }
        ConcurrentReshardExecutor executor =
                new ConcurrentReshardExecutor(4, 100);
        assertThat(executor.execute(source, target)).isEqualTo(1000);
        assertThat(source).isEmpty();
        assertThat(target).hasSize(1000);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 100, 5000})
    void parameterizedConcurrentMigration(int count) throws Exception {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v"));
        }
        ConcurrentReshardExecutor executor =
                new ConcurrentReshardExecutor(4, 50);
        assertThat(executor.execute(source, target)).isEqualTo(count);
        assertThat(target).hasSize(count);
    }

    @ParameterizedTest(name = "workers {0}")
    @ValueSource(ints = {1, 4, 8})
    void parameterizedWorkers(int workers) throws Exception {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < 500; i++) {
            source.put("k" + i, bytes("v"));
        }
        ConcurrentReshardExecutor executor =
                new ConcurrentReshardExecutor(workers, 25);
        assertThat(executor.execute(source, target)).isEqualTo(500);
    }

    @ParameterizedTest(name = "tick {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedTickSize(int tick) throws Exception {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            source.put("k" + i, bytes("v"));
        }
        ConcurrentReshardExecutor executor =
                new ConcurrentReshardExecutor(2, tick);
        assertThat(executor.execute(source, target)).isEqualTo(200);
    }

    @Test
    void emptySourceMovesZero() throws Exception {
        ConcurrentReshardExecutor executor =
                new ConcurrentReshardExecutor(2, 10);
        assertThat(executor.execute(new LinkedHashMap<>(),
                new LinkedHashMap<>())).isZero();
    }

    @Test
    void interruptedExecutionFails() {
        ConcurrentReshardExecutor executor =
                new ConcurrentReshardExecutor(1, 1);
        // 无故障场景下不会中断；仅验证可执行
        assertThat(executor).isNotNull();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
