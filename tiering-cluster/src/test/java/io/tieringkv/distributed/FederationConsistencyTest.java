package io.tieringkv.distributed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 多集群联邦一致性（ADR-0308）。 */
class FederationConsistencyTest {

    @Test
    void syncConverges() {
        FederationConsistencyVerifier verifier =
                new FederationConsistencyVerifier("a", "b");
        verifier.write("a", "k", "v1");
        verifier.sync("a", "b", "k");
        assertThat(verifier.syncs()).isEqualTo(1);
    }

    @Test
    void conflictDetected() {
        FederationConsistencyVerifier verifier =
                new FederationConsistencyVerifier("a", "b");
        verifier.write("a", "k", "v1");
        verifier.write("b", "k", "v2");
        verifier.sync("a", "b", "k");
        assertThat(verifier.conflicts()).isGreaterThan(0);
    }

    @Test
    void noConflictSingleWriter() {
        FederationConsistencyVerifier verifier =
                new FederationConsistencyVerifier("a", "b");
        verifier.write("a", "k", "v1");
        verifier.sync("a", "b", "k");
        verifier.sync("b", "a", "k");
        assertThat(verifier.conflicts()).isZero();
    }

    @ParameterizedTest(name = "rounds {0}")
    @MethodSource("rounds")
    void convergenceRounds(int rounds) {
        FederationConsistencyVerifier verifier =
                new FederationConsistencyVerifier("a", "b", "c");
        for (int i = 0; i < rounds; i++) {
            verifier.write("a", "k" + i, "v" + i);
            verifier.sync("a", "b", "k" + i);
            verifier.sync("b", "c", "k" + i);
        }
        assertThat(verifier.syncs()).isEqualTo(rounds * 2);
        assertThat(verifier.conflictRate()).isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "writers {0}")
    @MethodSource("writerCounts")
    void conflictRateBounded(int writers) {
        FederationConsistencyVerifier verifier =
                new FederationConsistencyVerifier("a", "b");
        for (int i = 0; i < writers; i++) {
            verifier.write("a", "k", "a" + i);
            verifier.write("b", "k", "b" + i);
            verifier.sync("a", "b", "k");
        }
        assertThat(verifier.conflictRate())
                .isLessThanOrEqualTo(1.0);
    }

    static Stream<Arguments> rounds() {
        return Stream.of(1, 5, 10, 20).map(Arguments::of);
    }

    static Stream<Arguments> writerCounts() {
        return Stream.of(1, 5, 10).map(Arguments::of);
    }
}
