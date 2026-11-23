package io.tieringkv.compliance;

import io.tieringkv.compliance.RegulationMapper.Control;
import io.tieringkv.compliance.RegulationVersion.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 法规版本库（ADR-0159）：生效版本选择 + 历史 + 切换。 */
class RegulationVersionStoreTest {

    @Test
    void effectiveReturnsLatestBeforeTime() {
        RegulationVersionStore store = store();
        Version version = store.effective("GDPR", 1500)
                .orElseThrow();
        assertThat(version.versionId()).isEqualTo("v1");
        Version latest = store.effective("GDPR", 2500)
                .orElseThrow();
        assertThat(latest.versionId()).isEqualTo("v2");
    }

    @Test
    void noVersionBeforeFirstEffective() {
        assertThat(store().effective("GDPR", 999)).isEmpty();
    }

    @Test
    void unknownRegulationEmpty() {
        assertThat(store().effective("SOX", 1000)).isEmpty();
    }

    @Test
    void historyOrderedByEffectiveTime() {
        RegulationVersionStore store = store();
        assertThat(store.history("GDPR"))
                .extracting(Version::versionId)
                .containsExactly("v1", "v2");
    }

    @Test
    void activatePublishesNewVersion() {
        RegulationVersionStore store = store();
        store.activate("GDPR", "v1", 3000);
        Version version = store.effective("GDPR", 3000)
                .orElseThrow();
        assertThat(version.controls()).hasSize(1);
        assertThat(store.versionCount("GDPR")).isEqualTo(3);
    }

    @Test
    void activateUnknownVersionRejected() {
        assertThatThrownBy(() -> store().activate(
                "GDPR", "missing", 3000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullVersionRejected() {
        assertThatThrownBy(() -> new RegulationVersionStore()
                .register(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerSameTimeOverwrites() {
        RegulationVersionStore store = new RegulationVersionStore();
        store.register(new Version("GDPR", "v1", 1000, Set.of()));
        store.register(new Version("GDPR", "v2", 1000,
                Set.of(new Control("g1", "d", true))));
        assertThat(store.versionCount("GDPR")).isEqualTo(1);
        assertThat(store.effective("GDPR", 1000).orElseThrow()
                .versionId()).isEqualTo("v2");
    }

    @ParameterizedTest(name = "time {0}")
    @ValueSource(longs = {0, 1_000, 1_000_000})
    void parameterizedEffectiveTimes(long time) {
        RegulationVersionStore store = new RegulationVersionStore();
        store.register(new Version("GDPR", "v1", 0, Set.of()));
        assertThat(store.effective("GDPR", time)).isPresent();
    }

    @ParameterizedTest(name = "versions {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedVersionCounts(int count) {
        RegulationVersionStore store = new RegulationVersionStore();
        for (int i = 0; i < count; i++) {
            store.register(new Version("GDPR", "v" + i,
                    i * 100L, Set.of()));
        }
        assertThat(store.versionCount("GDPR")).isEqualTo(count);
        assertThat(store.history("GDPR")).hasSize(count);
    }

    @Test
    void concurrentRegisterAndRead() throws Exception {
        RegulationVersionStore store = new RegulationVersionStore();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                store.register(new Version("GDPR", "v" + i,
                        i * 100L, Set.of()));
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                store.effective("GDPR", i * 100L);
                store.history("GDPR");
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(store.versionCount("GDPR")).isEqualTo(100);
    }

    @Test
    void rollbackViaActivateOldVersion() {
        RegulationVersionStore store = store();
        store.activate("GDPR", "v1", 2000);
        Version version = store.effective("GDPR", 2500)
                .orElseThrow();
        assertThat(version.controls()).hasSize(1);
    }

    private static RegulationVersionStore store() {
        RegulationVersionStore store = new RegulationVersionStore();
        store.register(new Version("GDPR", "v1", 1000,
                Set.of(new Control("g1", "residency", true))));
        store.register(new Version("GDPR", "v2", 2000,
                Set.of(new Control("g1", "residency", true),
                        new Control("g2", "audit", false))));
        return store;
    }
}
