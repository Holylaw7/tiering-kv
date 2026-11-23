package io.tieringkv.platform;

import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.cluster.scheduler.AutonomousPdScheduler;
import io.tieringkv.cluster.scheduler.GlobalAutonomyPdIntegration;
import io.tieringkv.cluster.topology.TopologyDiscovery;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.transaction.async.CrossRegionOnePhaseCommit;
import io.tieringkv.transaction.tso.TsoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 43 参数化边缘矩阵：跨区/多算子/TSO/自治/凭据。 */
class Phase43EdgeMatrixTest {

    @ParameterizedTest(name = "eligible={0} regions={1} expected={2}")
    @CsvSource({
            "1,1,true",
            "2,2,true",
            "3,3,true",
            "4,4,true",
            "5,5,true",
            "1,2,false",
            "2,3,false",
            "3,4,false",
            "4,5,false",
            "1,1,true",
            "2,2,true",
            "3,5,false",
            "4,6,false",
            "5,7,false",
            "2,1,true"
    })
    void crossRegionEligibilityMatrix(int eligible, int regions,
                                      boolean expected) {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i,
                    i < eligible);
        }
        Set<String> regionSet = new java.util.HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        assertThat(commit.commit("t", regionSet).onePhase())
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "ops={0} rows={1} threshold={2}")
    @CsvSource({
            "FILTER,5,10",
            "FILTER,10,50",
            "PROJECT,5,10",
            "PROJECT,10,100",
            "AGGREGATE,5,10",
            "AGGREGATE,10,1000",
            "FILTER,20,100",
            "PROJECT,20,100",
            "AGGREGATE,20,100",
            "FILTER,50,500",
            "PROJECT,50,500",
            "AGGREGATE,50,500",
            "FILTER,100,1000",
            "PROJECT,100,1000",
            "AGGREGATE,100,1000"
    })
    void operatorChainMatrix(String op, int rows,
                             double threshold) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + i, i * 10.0));
        }
        Operator operator = Operator.valueOf(op);
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(operator), "k0", "zz", threshold);
        List<Row> result = executor.executeCompound(request, data);
        assertThat(result).isNotNull();
        if (operator == Operator.AGGREGATE) {
            assertThat(result).hasSize(1);
        }
    }

    @ParameterizedTest(name = "batch={0} watermark={1}")
    @CsvSource({
            "1,0",
            "2,1",
            "5,4",
            "10,9",
            "20,19",
            "50,49",
            "100,99",
            "250,249",
            "500,499",
            "1000,999"
    })
    void tsoBatchWatermarkMatrix(int batch, long watermark) {
        TsoService tso = new TsoService();
        tso.allocate(batch);
        assertThat(tso.watermark()).isEqualTo(watermark);
    }

    @ParameterizedTest(name = "limit={0} nodes={1} moves={2}")
    @CsvSource({
            "1,2,1",
            "2,2,1",
            "1,4,2",
            "2,4,2",
            "5,4,2",
            "1,6,3",
            "3,6,3",
            "10,6,3",
            "2,8,4",
            "10,8,4"
    })
    void pdIntegrationLimitMatrix(int limit, int nodes,
                                  int overloaded) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < nodes; i++) {
            discovery.heartbeat(
                    new TopologyDiscovery.Heartbeat(
                            "n" + i, "r" + (i % 2),
                            "az-" + (i % 2), 0), 50);
        }
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r0", "g1", 0.1, 0.0, 100);
        autonomy.registerRegion("r1", "g1", 0.1, 0.0, 100);
        var integration = new GlobalAutonomyPdIntegration(
                discovery, new AutonomousPdScheduler(limit),
                autonomy, 100);
        Map<String, Long> loads = new LinkedHashMap<>();
        for (int i = 0; i < nodes; i++) {
            loads.put("n" + i,
                    i < overloaded ? 300L : 50L);
        }
        var result = integration.planAndExecute(loads);
        assertThat(result.executed())
                .isLessThanOrEqualTo(limit);
    }

    @ParameterizedTest(name = "mode={0} ok={1}")
    @CsvSource({
            "SIMULATED,true",
            "SIMULATED,false",
            "REAL,true",
            "REAL,false",
            "AUTO,true",
            "AUTO,false",
            "SIMULATED,true",
            "REAL,true",
            "REAL,false",
            "AUTO,true"
    })
    void credentialModeMatrix(String mode, boolean ok) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.valueOf(mode),
                (endpoint, timeout) -> ok, 1000);
        var result = probe.probe("s3",
                ok ? "https://s3.example.com" : "",
                ok ? "secret" : "");
        assertThat(result.degraded()).isEqualTo(!ok);
    }

    @Test
    void compoundRequestNullOperatorsRejected() {
        assertThatThrownBy(() -> new CompoundCoprocessorRequest(
                null, "a", "z", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compoundRequestEmptyOperatorsRejected() {
        assertThatThrownBy(() -> new CompoundCoprocessorRequest(
                List.of(), "a", "z", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tsoRestoreNeverGoesBackwards() {
        TsoService tso = new TsoService();
        tso.allocate(100);
        assertThat(tso.restore(50)).isEqualTo(99);
        assertThat(tso.restore(200)).isEqualTo(200);
        assertThat(tso.restore(150)).isEqualTo(200);
    }

    @Test
    void crossRegionTwoPhaseAlwaysTwoPhase() {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        commit.registerPrimaryReplica("r1", true);
        var result = commit.commitTwoPhase("t",
                Set.of("r1"));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }
}
