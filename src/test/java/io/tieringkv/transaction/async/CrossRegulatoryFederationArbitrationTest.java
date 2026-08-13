package io.tieringkv.transaction.async;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨监管域联邦仲裁（ADR-0256）：域边界 + 域级仲裁 + 回退 2PC。 */
class CrossRegulatoryFederationArbitrationTest {

    private static final List<String> CLOUDS = List.of(
            "cloud-a1", "cloud-a2", "cloud-b1", "cloud-b2",
            "cloud-c1", "cloud-c2");

    private CrossRegulatoryFederationArbitration topology() {
        CrossRegulatoryFederationArbitration arbitration =
                new CrossRegulatoryFederationArbitration();
        arbitration.registerDomain("cloud-a1", "EU");
        arbitration.registerDomain("cloud-a2", "EU");
        arbitration.registerDomain("cloud-b1", "US");
        arbitration.registerDomain("cloud-b2", "US");
        arbitration.registerDomain("cloud-c1", "CN");
        arbitration.registerDomain("cloud-c2", "CN");
        for (String cloud : CLOUDS) {
            arbitration.registerZone(cloud, "z1", true);
            arbitration.registerZone(cloud, "z2", true);
            arbitration.registerZone(cloud, "z3", true);
        }
        return arbitration;
    }

    private void markIneligible(
            CrossRegulatoryFederationArbitration arbitration,
            String cloud) {
        arbitration.registerZone(cloud, "z1", false);
        arbitration.registerZone(cloud, "z2", false);
        arbitration.registerZone(cloud, "z3", false);
    }

    @Test
    void allEligibleDomainsCommitOnePhase() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        var result = arbitration.commit("t1",
                Set.of("cloud-a1", "cloud-b1"));
        assertThat(result.onePhase()).isTrue();
        assertThat(result.fallback2Pc()).isFalse();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.domains()).isEqualTo(2);
        assertThat(result.eligibleDomains()).isEqualTo(2);
    }

    @Test
    void ineligibleDomainFallsBackTo2Pc() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        markIneligible(arbitration, "cloud-c1");
        var result = arbitration.commit("t2", Set.of(
                "cloud-a1", "cloud-c1"));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.fallback2Pc()).isTrue();
    }

    @Test
    void singleDomainCommitStillOnePhase() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        var result = arbitration.commit("t3",
                Set.of("cloud-a1", "cloud-a2"));
        assertThat(result.onePhase()).isTrue();
    }

    @Test
    void commitIsIdempotent() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        var first = arbitration.commit("t4",
                Set.of("cloud-a1", "cloud-b1"));
        var second = arbitration.commit("t4",
                Set.of("cloud-a1", "cloud-b1"));
        assertThat(second).isEqualTo(first);
        assertThat(arbitration.completedCount()).isEqualTo(1);
    }

    @Test
    void resolvedTimestampAdvancesOnOnePhase() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        arbitration.attachResolvedTimestamp(resolved);
        arbitration.commit("t5", Set.of("cloud-a1"),
                100);
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void multiOrgFallbackPropagates() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        MultiOrgFederationArbitration multiOrg =
                new MultiOrgFederationArbitration();
        multiOrg.registerOrganization("cloud-a1", "org-eu");
        multiOrg.registerOrganization("cloud-b1", "org-us");
        multiOrg.registerZone("cloud-a1", "z1", false);
        multiOrg.registerZone("cloud-a1", "z2", false);
        arbitration.attachMultiOrg(multiOrg);
        var result = arbitration.commit("t6",
                Set.of("cloud-a1", "cloud-b1"));
        assertThat(result.fallback2Pc()).isTrue();
    }

    @Test
    void globalUnifiedFallbackPropagates() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        GlobalUnifiedOnePhaseArbitration global =
                new GlobalUnifiedOnePhaseArbitration();
        global.registerZone("cloud-a1", "z1", false);
        global.registerZone("cloud-a1", "z2", false);
        arbitration.attachGlobal(global);
        var result = arbitration.commit("t7",
                Set.of("cloud-a1", "cloud-b1"));
        assertThat(result.fallback2Pc()).isTrue();
    }

    @Test
    void domainBoundaryDiscoveryWorks() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        assertThat(arbitration.domainOf("cloud-a1"))
                .isEqualTo("EU");
        assertThat(arbitration.domainOf("cloud-c2"))
                .isEqualTo("CN");
    }

    @Test
    void unknownCloudDefaultsToDefaultDomain() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        assertThat(arbitration.domainOf("other"))
                .isEqualTo("default");
    }

    @Test
    void topologyVersionBumpsOnRegister() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        long before = arbitration.topologyVersion();
        arbitration.registerDomain("cloud-d1", "APAC");
        assertThat(arbitration.topologyVersion())
                .isGreaterThan(before);
    }

    @Test
    void blankTxnIdRejected() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        assertThatThrownBy(() -> arbitration.commit("",
                Set.of("cloud-a1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyCloudsRejected() {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        assertThatThrownBy(() -> arbitration.commit("t9",
                Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "one-phase clouds {0}")
    @MethodSource("onePhaseCloudSets")
    void allEligibleCombinationsCommitOnePhase(Set<String> clouds) {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        var result = arbitration.commit("tx", clouds);
        assertThat(result.onePhase()).isTrue();
        assertThat(result.fallback2Pc()).isFalse();
    }

    @ParameterizedTest(name = "fallback ineligible {0}")
    @MethodSource("ineligibleCloudSets")
    void ineligibleCombinationsFallback2Pc(Set<String> clouds) {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        for (String cloud : clouds) {
            markIneligible(arbitration, cloud);
        }
        var result = arbitration.commit("ty", Set.copyOf(CLOUDS));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.fallback2Pc()).isTrue();
    }

    @ParameterizedTest(name = "idempotency {0}")
    @MethodSource("idempotencyCloudSets")
    void repeatedCommitsReturnSameResult(Set<String> clouds) {
        CrossRegulatoryFederationArbitration arbitration =
                topology();
        var first = arbitration.commit("tz", clouds);
        var second = arbitration.commit("tz", clouds);
        assertThat(second).isEqualTo(first);
    }

    static Stream<Arguments> onePhaseCloudSets() {
        List<Set<String>> sets = new ArrayList<>();
        for (int size = 2; size <= CLOUDS.size(); size++) {
            combinations(CLOUDS, size, new ArrayList<>(),
                    0, sets);
        }
        return sets.stream().map(Arguments::of);
    }

    static Stream<Arguments> ineligibleCloudSets() {
        List<Set<String>> sets = new ArrayList<>();
        for (String cloud : CLOUDS) {
            sets.add(Set.of(cloud));
        }
        for (int i = 0; i < CLOUDS.size(); i++) {
            for (int j = i + 1; j < CLOUDS.size(); j++) {
                sets.add(Set.of(CLOUDS.get(i), CLOUDS.get(j)));
            }
        }
        return sets.stream().map(Arguments::of);
    }

    static Stream<Arguments> idempotencyCloudSets() {
        return Stream.of(
                Arguments.of(Set.of("cloud-a1")),
                Arguments.of(Set.of("cloud-a1", "cloud-b2")),
                Arguments.of(Set.of("cloud-a1", "cloud-a2")),
                Arguments.of(Set.of("cloud-c1", "cloud-c2")),
                Arguments.of(Set.of("cloud-a1", "cloud-b1",
                        "cloud-c1")),
                Arguments.of(Set.copyOf(CLOUDS)),
                Arguments.of(Set.of("cloud-b1", "cloud-b2",
                        "cloud-c1")));
    }

    private static void combinations(List<String> items,
                                     int size,
                                     List<String> prefix,
                                     int start,
                                     List<Set<String>> out) {
        if (prefix.size() == size) {
            out.add(new HashSet<>(prefix));
            return;
        }
        for (int i = start; i < items.size(); i++) {
            prefix.add(items.get(i));
            combinations(items, size, prefix, i + 1, out);
            prefix.remove(prefix.size() - 1);
        }
    }
}
