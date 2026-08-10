package io.tieringkv.storage.tiering;

import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BackPressureTest {

    @Test
    void criticalTimesOut() {
        MemoryManager memory = new MemoryManager(100);
        memory.add(100);
        BackPressureController controller = new BackPressureController(
                new WatermarkManager(WatermarkManager.Config.defaults()),
                memory, () -> 0, () -> 0);
        assertThat(controller.currentState()).isEqualTo(TierState.CRITICAL);
        assertThat(controller.awaitWritable(100)).isFalse();
    }

    @Test
    void warningDoesNotBlock() {
        MemoryManager memory = new MemoryManager(100);
        memory.add(85);
        BackPressureController controller = new BackPressureController(
                new WatermarkManager(WatermarkManager.Config.defaults()),
                memory, () -> 0, () -> 0);
        assertThat(controller.currentState()).isEqualTo(TierState.WARNING);
        assertThat(controller.awaitWritable(100)).isTrue();
    }

    @Test
    void drainReleasesWaiter() throws Exception {
        MemoryManager memory = new MemoryManager(100);
        memory.add(100);
        BackPressureController controller = new BackPressureController(
                new WatermarkManager(WatermarkManager.Config.defaults()),
                memory, () -> 0, () -> 0);
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(
                () -> controller.awaitWritable(3000));
        Thread.sleep(100);
        memory.remove(100);
        controller.notifyStateChanged();
        assertThat(result.get(2, TimeUnit.SECONDS)).isTrue();
    }
}
