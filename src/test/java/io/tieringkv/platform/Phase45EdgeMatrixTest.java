package io.tieringkv.platform;

import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.transaction.async.MultiCloudOnePhaseCommit;
import io.tieringkv.transaction.tso.GlobalTsoClock;
import io.tieringkv.transaction.tso.GlobalTsoClock.TimeSource;
import io.tieringkv.transaction.tso.GlobalTsoClock.TimeSourceType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 45 参数化边缘矩阵：跨云/窗口/时钟/凭据。 */
class Phase45EdgeMatrixTest {

    @ParameterizedTest(name = "{0} a={1} b={2} c={3}")
    @CsvSource({
            "CLOUD,1,1,1",
            "CLOUD,2,2,1",
            "CLOUD,2,1,0",
            "CLOUD,3,2,1",
            "CLOUD,3,1,0",
            "CLOUD,4,3,1",
            "CLOUD,4,2,0",
            "CLOUD,5,3,1",
            "CLOUD,5,2,0",
            "WINDOW,3,1,3",
            "WINDOW,5,2,5",
            "WINDOW,10,3,10",
            "CLOCK,100,200,100",
            "CLOCK,1000,1100,100",
            "CLOCK,5000,5100,100",
            "CRED,1,1,1",
            "CRED,1,0,0",
            "CRED,0,1,0",
            "CRED,0,0,0",
            "CLOUD,6,4,1",
            "CLOUD,6,3,0",
            "WINDOW,20,4,20",
            "CLOCK,10000,10100,100",
            "CRED,1,1,1",
            "CRED,1,0,0"
    })
    void edgeMatrix(String feature, int a, int b, int c) {
        switch (feature) {
            case "CLOUD" -> cloudEdge(a, b, c == 1);
            case "WINDOW" -> windowEdge(a, b);
            case "CLOCK" -> clockEdge(a, b, c);
            case "CRED" -> credEdge(a == 1, b == 1);
            default -> throw new IllegalArgumentException(
                    "unknown feature " + feature);
        }
    }

    private static void cloudEdge(int clouds, int eligible,
                                  boolean allEligible) {
        MultiCloudOnePhaseCommit commit =
                new MultiCloudOnePhaseCommit();
        for (int i = 0; i < clouds; i++) {
            commit.registerCloud("c" + i, i < eligible);
        }
        Set<String> cloudSet = new java.util.HashSet<>();
        for (int i = 0; i < clouds; i++) {
            cloudSet.add("c" + i);
        }
        assertThat(commit.commit("t", cloudSet).onePhase())
                .isEqualTo(allEligible);
    }

    private static void windowEdge(int rows, int partitions) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("p" + (i % partitions), i));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.WINDOW),
                        "p0", "zz", 0, List.of(), List.of(),
                        Integer.MAX_VALUE, false,
                        CompoundCoprocessorRequest.WindowFunction
                                .ROW_NUMBER);
        assertThat(executor.executeCompound(request, data))
                .hasSize(rows);
    }

    private static void clockEdge(long t1, long t2, long skew) {
        GlobalTsoClock clock = new GlobalTsoClock(
                List.of(new TimeSource(TimeSourceType.NTP, t1),
                        new TimeSource(TimeSourceType.GPS, t2)),
                skew);
        assertThat(clock.now()).isBetween(
                Math.min(t1, t2), Math.max(t1, t2));
        long first = clock.timestamp();
        assertThat(clock.timestamp())
                .isGreaterThanOrEqualTo(first);
    }

    private static void credEdge(boolean reachable,
                                 boolean authValid) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> reachable, 500);
        var result = probe.probeAuthenticated("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> reachable,
                (endpoint, credential) -> authValid);
        assertThat(result.ok())
                .isEqualTo(reachable && authValid);
    }
}
