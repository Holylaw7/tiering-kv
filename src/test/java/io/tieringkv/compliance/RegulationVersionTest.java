package io.tieringkv.compliance;

import io.tieringkv.compliance.RegulationMapper.Control;
import io.tieringkv.compliance.RegulationVersion.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 法规版本（ADR-0159）：生效时间 + 控制项快照。 */
class RegulationVersionTest {

    @Test
    void versionEffectiveAfterTime() {
        Version version = version(1000);
        assertThat(version.isEffective(1000)).isTrue();
        assertThat(version.isEffective(2000)).isTrue();
        assertThat(version.isEffective(999)).isFalse();
    }

    @Test
    void versionCarriesRegulationAndId() {
        Version version = version(1000);
        assertThat(version.regulation()).isEqualTo("GDPR");
        assertThat(version.versionId()).isEqualTo("v1");
    }

    @Test
    void controlsAreCopied() {
        Set<Control> controls = new java.util.HashSet<>();
        controls.add(new Control("g1", "residency", true));
        Version version = new Version("GDPR", "v1", 1000, controls);
        controls.add(new Control("g2", "audit", false));
        assertThat(version.controls()).hasSize(1);
    }

    @Test
    void blankRegulationRejected() {
        assertThatThrownBy(() -> new Version("", "v1", 1000,
                Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankVersionIdRejected() {
        assertThatThrownBy(() -> new Version("GDPR", " ", 1000,
                Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeEffectiveTimeRejected() {
        assertThatThrownBy(() -> new Version("GDPR", "v1", -1,
                Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyControlsAllowed() {
        Version version = new Version("GDPR", "v1", 1000, Set.of());
        assertThat(version.controls()).isEmpty();
    }

    @ParameterizedTest(name = "time {0}")
    @ValueSource(longs = {0, 1_000, 1_000_000})
    void parameterizedEffectiveTimes(long time) {
        Version version = new Version("GDPR", "v1", time, Set.of());
        assertThat(version.isEffective(time)).isTrue();
        assertThat(version.isEffective(time - 1)).isFalse();
    }

    @ParameterizedTest(name = "controls {0}")
    @ValueSource(ints = {0, 1, 5})
    void parameterizedControlCounts(int count) {
        Set<Control> controls = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            controls.add(new Control("c" + i, "d", true));
        }
        Version version = new Version("GDPR", "v1", 0, controls);
        assertThat(version.controls()).hasSize(count);
    }

    @Test
    void nullControlsRejected() {
        assertThatThrownBy(() -> new Version("GDPR", "v1", 0,
                null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void versionsAreImmutable() {
        Version version = version(1000);
        assertThat(version.isEffective(1000)).isTrue();
        assertThat(version.isEffective(999)).isFalse();
    }

    @Test
    void distinctVersionsSameRegulation() {
        Version first = new Version("GDPR", "v1", 1000, Set.of());
        Version second = new Version("GDPR", "v2", 2000, Set.of());
        assertThat(first.versionId()).isNotEqualTo(second.versionId());
    }

    private static Version version(long effectiveFrom) {
        return new Version("GDPR", "v1", effectiveFrom,
                Set.of(new Control("g1", "residency", true)));
    }
}
