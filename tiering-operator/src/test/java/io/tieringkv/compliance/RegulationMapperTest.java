package io.tieringkv.compliance;

import io.tieringkv.compliance.RegulationMapper.Control;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 法规映射（ADR-0153）：法规 → 控制项 + 覆盖率。 */
class RegulationMapperTest {

    @Test
    void registerAndQueryControls() {
        RegulationMapper mapper = new RegulationMapper();
        mapper.register("GDPR", new Control("g1", "data residency",
                        true),
                new Control("g2", "audit log", false));
        assertThat(mapper.controls("GDPR")).hasSize(2);
        assertThat(mapper.regulations()).containsExactly("GDPR");
    }

    @Test
    void unknownRegulationEmpty() {
        assertThat(new RegulationMapper().controls("SOX")).isEmpty();
        assertThat(new RegulationMapper().coverage("SOX")).isZero();
    }

    @Test
    void coverageImplementedOnly() {
        RegulationMapper mapper = mapper();
        assertThat(mapper.coverage("GDPR")).isEqualTo(0.5);
        assertThat(mapper.coverage("SOC2")).isEqualTo(1.0);
    }

    @Test
    void coversControl() {
        RegulationMapper mapper = mapper();
        assertThat(mapper.covers("GDPR", "g1")).isTrue();
        assertThat(mapper.covers("GDPR", "missing")).isFalse();
    }

    @Test
    void missingControlsListed() {
        RegulationMapper mapper = mapper();
        assertThat(mapper.missingControls("GDPR"))
                .containsExactly("g2");
    }

    @Test
    void noMissingWhenFullyImplemented() {
        RegulationMapper mapper = mapper();
        assertThat(mapper.missingControls("SOC2")).isEmpty();
    }

    @Test
    void blankRegulationRejected() {
        assertThatThrownBy(() -> new RegulationMapper()
                .register("", new Control("c", "d", true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullRegulationRejected() {
        assertThatThrownBy(() -> new RegulationMapper()
                .register(null, new Control("c", "d", true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankControlIdRejected() {
        assertThatThrownBy(() -> new Control("", "d", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyControlSetAllowed() {
        RegulationMapper mapper = new RegulationMapper();
        mapper.register("GDPR");
        assertThat(mapper.controlCount("GDPR")).isZero();
        assertThat(mapper.coverage("GDPR")).isZero();
    }

    @Test
    void multipleRegulationsIndependent() {
        RegulationMapper mapper = mapper();
        assertThat(mapper.controlCount("GDPR")).isEqualTo(2);
        assertThat(mapper.controlCount("SOC2")).isEqualTo(1);
    }

    @Test
    void duplicateRegistrationOverwrites() {
        RegulationMapper mapper = new RegulationMapper();
        mapper.register("GDPR", new Control("g1", "a", true));
        mapper.register("GDPR", new Control("g1", "a", false),
                new Control("g2", "b", true));
        assertThat(mapper.controlCount("GDPR")).isEqualTo(2);
    }

    @ParameterizedTest(name = "regulation {0}")
    @ValueSource(strings = {"GDPR", "SOC2", "PCI-DSS"})
    void parameterizedRegulations(String regulation) {
        RegulationMapper mapper = new RegulationMapper();
        mapper.register(regulation, new Control("c1", "d", true));
        assertThat(mapper.controls(regulation)).hasSize(1);
        assertThat(mapper.coverage(regulation)).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "implemented {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    void parameterizedCoverage(int implemented) {
        RegulationMapper mapper = new RegulationMapper();
        RegulationMapper.Control[] controls =
                new RegulationMapper.Control[3];
        for (int i = 0; i < 3; i++) {
            controls[i] = new Control("c" + i, "d" + i,
                    i < implemented);
        }
        mapper.register("R", controls);
        assertThat(mapper.coverage("R"))
                .isEqualTo(implemented / 3.0);
    }

    @Test
    void concurrentRegistration() throws Exception {
        RegulationMapper mapper = new RegulationMapper();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                mapper.register("R" + i, new Control("c", "d",
                        true));
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                mapper.controls("R" + (i % 50));
                mapper.coverage("R" + (i % 50));
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(mapper.regulations()).hasSize(50);
    }

    @Test
    void coverageNeverExceedsOne() {
        RegulationMapper mapper = new RegulationMapper();
        mapper.register("R", new Control("c1", "d", true),
                new Control("c2", "d", true));
        assertThat(mapper.coverage("R")).isEqualTo(1.0);
    }

    private static RegulationMapper mapper() {
        RegulationMapper mapper = new RegulationMapper();
        mapper.register("GDPR", new Control("g1", "data residency",
                        true),
                new Control("g2", "audit log", false));
        mapper.register("SOC2", new Control("s1", "access control",
                true));
        return mapper;
    }
}
