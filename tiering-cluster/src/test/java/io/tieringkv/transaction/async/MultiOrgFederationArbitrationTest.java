package io.tieringkv.transaction.async;

import io.tieringkv.transaction.async.MultiOrgFederationArbitration
        .FederationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多组织联邦仲裁（ADR-0249）：组织边界 + 组织级仲裁。 */
class MultiOrgFederationArbitrationTest {

    @Test
    void allOrgsEligibleOnePhase() {
        MultiOrgFederationArbitration arbitration =
                federation(2, 2, 3, 2);
        FederationResult result = arbitration.commit("t1",
                clouds(2, 2));
        assertThat(result.onePhase()).isTrue();
        assertThat(result.eligibleOrganizations()).isEqualTo(2);
    }

    @Test
    void minorityOrgFallsBack() {
        MultiOrgFederationArbitration arbitration =
                federation(3, 2, 3, 1);
        assertThat(arbitration.commit("t1",
                clouds(3, 2)).onePhase()).isFalse();
    }

    @Test
    void singleOrgQuorum() {
        MultiOrgFederationArbitration arbitration =
                federation(1, 2, 3, 2);
        assertThat(arbitration.commit("t1",
                clouds(1, 2)).onePhase()).isTrue();
    }

    @Test
    void unknownOrgCloudsDefault() {
        MultiOrgFederationArbitration arbitration =
                federation(2, 1, 3, 2);
        assertThat(arbitration.commit("t1",
                Set.of("c1-1", "c2-1", "ghost"))
                .onePhase()).isTrue();
    }

    @Test
    void federationVersionIncrements() {
        MultiOrgFederationArbitration arbitration =
                federation(1, 1, 2, 2);
        long v1 = arbitration.federationVersion();
        arbitration.registerOrganization("c1", "org-9");
        assertThat(arbitration.federationVersion())
                .isGreaterThan(v1);
    }

    @Test
    void blankTxnIdRejected() {
        assertThatThrownBy(() -> federation(2, 1, 3, 2)
                .commit("", Set.of("c1", "c2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyCloudsRejected() {
        assertThatThrownBy(() -> federation(2, 1, 3, 2)
                .commit("t1", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvedTsAdvanced() {
        MultiOrgFederationArbitration arbitration =
                federation(2, 2, 3, 2);
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        arbitration.attachResolvedTimestamp(resolved);
        arbitration.commit("t1", clouds(2, 2), 100);
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void resolvedTsNotAdvancedOnMinority() {
        MultiOrgFederationArbitration arbitration =
                federation(3, 2, 3, 1);
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        arbitration.attachResolvedTimestamp(resolved);
        arbitration.commit("t1", clouds(3, 2), 100);
        assertThat(resolved.resolvedTs()).isZero();
    }

    @Test
    void commitIdempotent() {
        MultiOrgFederationArbitration arbitration =
                federation(2, 2, 3, 2);
        FederationResult first = arbitration.commit("t1",
                clouds(2, 2));
        FederationResult second = arbitration.commit("t1",
                clouds(2, 2));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void concurrentCommitStable() throws Exception {
        MultiOrgFederationArbitration arbitration =
                federation(3, 2, 3, 2);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(arbitration.commit("t" + i,
                            clouds(3, 2)).succeeded())
                            .isTrue();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @Test
    void mixedOrgEligibility() {
        MultiOrgFederationArbitration arbitration =
                new MultiOrgFederationArbitration();
        arbitration.registerOrganization("c1", "org-a");
        arbitration.registerOrganization("c2", "org-a");
        arbitration.registerOrganization("c3", "org-b");
        arbitration.registerZone("c1", "z1", true);
        arbitration.registerZone("c1", "z2", true);
        arbitration.registerZone("c1", "z3", false);
        arbitration.registerZone("c2", "z1", false);
        arbitration.registerZone("c2", "z2", true);
        arbitration.registerZone("c2", "z3", true);
        arbitration.registerZone("c3", "z1", true);
        arbitration.registerZone("c3", "z2", true);
        arbitration.registerZone("c3", "z3", true);
        // org-a: c1 ok, c2 ok → 合格；org-b: c3 ok → 合格 → 2>1
        assertThat(arbitration.commit("t1",
                Set.of("c1", "c2", "c3")).onePhase()).isTrue();
    }

    @ParameterizedTest(name = "orgs={0} cpo={1} zones={2} elig={3}")
    @CsvSource({
            "1,1,1,1,true",
            "1,2,2,2,true",
            "1,2,2,1,false",
            "1,3,3,2,true",
            "1,3,3,1,false",
            "2,1,3,2,true",
            "2,1,3,1,false",
            "2,2,3,2,true",
            "2,2,3,1,false",
            "2,2,2,2,true",
            "2,2,2,1,false",
            "3,2,3,2,true",
            "3,2,3,1,false",
            "3,3,2,2,true",
            "3,3,2,1,false",
            "4,2,3,2,true",
            "4,2,3,1,false",
            "4,3,4,3,true",
            "4,3,4,2,false",
            "5,2,3,2,true",
            "5,2,3,1,false",
            "5,3,5,3,true",
            "5,3,5,2,false",
            "6,2,3,2,true",
            "6,2,3,1,false",
            "6,3,4,3,true",
            "6,3,4,2,false",
            "7,2,3,2,true",
            "7,2,3,1,false",
            "7,3,5,3,true",
            "7,3,5,2,false",
            "8,2,3,2,true",
            "8,2,3,1,false",
            "8,3,6,4,true",
            "8,3,6,3,false"
    })
    void parameterizedFederationMatrix(int orgs, int cloudsPerOrg,
                                       int zones,
                                       int eligibleZones,
                                       boolean expected) {
        MultiOrgFederationArbitration arbitration =
                federation(orgs, cloudsPerOrg, zones,
                        eligibleZones);
        assertThat(arbitration.commit("t",
                clouds(orgs, cloudsPerOrg)).onePhase())
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "orgs={0} cpo={1} zones={2} repeats={3}")
    @CsvSource({
            "1,2,3,2",
            "1,2,3,5",
            "2,2,3,2",
            "2,2,3,5",
            "3,2,3,2",
            "3,2,3,5",
            "4,2,3,2",
            "4,2,3,5",
            "5,2,3,2",
            "5,2,3,5",
            "2,1,2,10",
            "3,1,2,10",
            "4,1,2,10",
            "5,1,2,10",
            "1,1,2,10",
            "3,2,4,3",
            "4,2,4,4",
            "5,2,4,3",
            "2,2,5,8",
            "6,1,2,2"
    })
    void parameterizedCommitIdempotent(int orgs, int cloudsPerOrg,
                                       int zones, int repeats) {
        MultiOrgFederationArbitration arbitration =
                federation(orgs, cloudsPerOrg, zones,
                        Math.max(1, zones / 2 + 1));
        FederationResult first = arbitration.commit("t",
                clouds(orgs, cloudsPerOrg));
        for (int i = 1; i < repeats; i++) {
            assertThat(arbitration.commit("t",
                    clouds(orgs, cloudsPerOrg)))
                    .isEqualTo(first);
        }
    }

    @ParameterizedTest(name = "orgs {0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 9, 11})
    void parameterizedOrgCounts(int orgs) {
        MultiOrgFederationArbitration arbitration =
                federation(orgs, 2, 3, 2);
        assertThat(arbitration.commit("t",
                clouds(orgs, 2)).onePhase()).isTrue();
    }

    @ParameterizedTest(name = "orgs={0} cpo={1} elig={2} ts={3}")
    @CsvSource({
            "2,2,2,100,100",
            "3,2,2,200,200",
            "5,2,2,300,300",
            "2,2,1,100,0",
            "3,2,1,200,0"
    })
    void parameterizedResolvedTs(int orgs, int cloudsPerOrg,
                                 int eligibleZones, long ts,
                                 long expected) {
        MultiOrgFederationArbitration arbitration =
                federation(orgs, cloudsPerOrg, 3,
                        eligibleZones);
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        arbitration.attachResolvedTimestamp(resolved);
        arbitration.commit("t", clouds(orgs, cloudsPerOrg),
                ts);
        assertThat(resolved.resolvedTs()).isEqualTo(expected);
    }

    private static MultiOrgFederationArbitration federation(
            int orgs, int cloudsPerOrg, int zones,
            int eligibleZones) {
        MultiOrgFederationArbitration arbitration =
                new MultiOrgFederationArbitration();
        for (int o = 1; o <= orgs; o++) {
            for (int c = 1; c <= cloudsPerOrg; c++) {
                String cloud = "c" + o + "-" + c;
                arbitration.registerOrganization(cloud,
                        "org-" + o);
                for (int z = 1; z <= zones; z++) {
                    arbitration.registerZone(cloud, "z" + z,
                            z <= eligibleZones);
                }
            }
        }
        return arbitration;
    }

    private static Set<String> clouds(int orgs,
                                      int cloudsPerOrg) {
        Set<String> clouds = new HashSet<>();
        for (int o = 1; o <= orgs; o++) {
            for (int c = 1; c <= cloudsPerOrg; c++) {
                clouds.add("c" + o + "-" + c);
            }
        }
        return clouds;
    }
}
