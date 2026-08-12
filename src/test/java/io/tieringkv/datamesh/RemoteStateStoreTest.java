package io.tieringkv.datamesh;

import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import io.tieringkv.datamesh.RemoteStateStore.PersistedState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 远端状态持久化（ADR-0179）：落盘/恢复/损坏回退。 */
class RemoteStateStoreTest {

    @TempDir
    Path dir;

    @Test
    void saveAndLoadRoundTrip() {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("v1", snapshot("v1", 42, 2, false, 1000),
                Map.of("k1", 10.0, "k2", 32.0));
        PersistedState state = store.load("v1").orElseThrow();
        assertThat(state.viewId()).isEqualTo("v1");
        assertThat(state.value()).isEqualTo(42);
        assertThat(state.count()).isEqualTo(2);
        assertThat(state.stale()).isFalse();
        assertThat(state.keys()).containsEntry("k1", 10.0);
    }

    @Test
    void missingFileEmpty() {
        assertThat(new RemoteStateStore(dir).load("missing"))
                .isEmpty();
    }

    @Test
    void corruptFileEmpty() throws Exception {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("v1", snapshot("v1", 1, 1, false, 1),
                Map.of());
        Path file = dir.resolve("v1.state");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x7F;
        Files.write(file, bytes);
        assertThat(store.load("v1")).isEmpty();
    }

    @Test
    void truncatedFileEmpty() throws Exception {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("v1", snapshot("v1", 1, 1, false, 1),
                Map.of());
        Path file = dir.resolve("v1.state");
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes,
                bytes.length - 3));
        assertThat(store.load("v1")).isEmpty();
    }

    @Test
    void multipleViewsIndependent() {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("a", snapshot("a", 1, 1, true, 1), Map.of());
        store.save("b", snapshot("b", 2, 2, false, 2),
                Map.of("k", 2.0));
        assertThat(store.load("a").orElseThrow().value())
                .isEqualTo(1);
        assertThat(store.load("b").orElseThrow().keys())
                .containsEntry("k", 2.0);
    }

    @Test
    void staleFlagPersisted() {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("v1", snapshot("v1", 0, 0, true, 0), Map.of());
        assertThat(store.load("v1").orElseThrow().stale()).isTrue();
    }

    @Test
    void deleteRemovesState() {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("v1", snapshot("v1", 1, 1, false, 1), Map.of());
        store.delete("v1");
        assertThat(store.load("v1")).isEmpty();
    }

    @Test
    void reopenedStoreLoads() {
        RemoteStateStore first = new RemoteStateStore(dir);
        first.save("v1", snapshot("v1", 7, 3, false, 9),
                Map.of("k", 7.0));
        RemoteStateStore reopened = new RemoteStateStore(dir);
        assertThat(reopened.load("v1").orElseThrow().value())
                .isEqualTo(7);
    }

    @Test
    void overwriteUpdatesState() {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("v1", snapshot("v1", 1, 1, false, 1), Map.of());
        store.save("v1", snapshot("v1", 9, 1, false, 2),
                Map.of("k", 9.0));
        PersistedState state = store.load("v1").orElseThrow();
        assertThat(state.value()).isEqualTo(9);
        assertThat(state.keys()).containsEntry("k", 9.0);
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedKeyCounts(int count) {
        RemoteStateStore store = new RemoteStateStore(dir);
        Map<String, Double> keys = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            keys.put("k" + i, i * 1.0);
        }
        store.save("v1", snapshot("v1", count, count, false, 1),
                keys);
        assertThat(store.load("v1").orElseThrow().keys())
                .hasSize(count);
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {1, 10, 50})
    void parameterizedViewCounts(int count) {
        RemoteStateStore store = new RemoteStateStore(dir);
        for (int i = 0; i < count; i++) {
            store.save("v" + i, snapshot("v" + i, i, 1, false, i),
                    Map.of());
        }
        for (int i = 0; i < count; i++) {
            assertThat(store.load("v" + i).orElseThrow().value())
                    .isEqualTo(i);
        }
    }

    @Test
    void concurrentSaveLoadStable() throws Exception {
        RemoteStateStore store = new RemoteStateStore(dir);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                store.save("v" + (i % 5),
                        snapshot("v" + (i % 5), i, 1, false, i),
                        Map.of("k", (double) i));
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 500; i++) {
                store.load("v" + (i % 5));
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(store.load("v0")).isPresent();
    }

    @Test
    void blankViewIdRejected() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new RemoteStateStore(dir).save("",
                        snapshot("v", 1, 1, false, 1), Map.of())))
                .isNotNull();
    }

    private static RemoteSnapshot snapshot(String viewId,
                                           double value, long count,
                                           boolean stale,
                                           long refreshedAt) {
        return new RemoteSnapshot(viewId, "gcp-us", value, count,
                stale, refreshedAt);
    }
}
