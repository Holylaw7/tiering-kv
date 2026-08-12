package io.tieringkv.compliance;

import io.tieringkv.compliance.ChainAnchor.AnchorRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨链互操作（ADR-0195）：多链锚定 + 一致性。 */
class CrossChainAnchorTest {

    private final CrossChainAnchor anchor = new CrossChainAnchor();
    private final CrossChainVerifier verifier =
            new CrossChainVerifier();

    @Test
    void anchorSingleChain() {
        AnchorRecord record = anchor.anchor("chain-1",
                "block-1", 1000, "head");
        assertThat(verifier.verifyAny(
                List.of(anchor.record("chain-1")))).isTrue();
    }

    @Test
    void anchorAllChains() {
        Map<String, AnchorRecord> records = anchor.anchorAll(
                Set.of("chain-1", "chain-2"), 1000, "head");
        assertThat(records).hasSize(2);
        assertThat(verifier.verifyConsistent(
                records.values())).isTrue();
    }

    @Test
    void verifyAnySucceedsWithOneValid() {
        anchor.anchor("chain-1", "block-1", 1000, "head");
        AnchorRecord tampered = new AnchorRecord("chain-2",
                "block-2", 1000, "head", "badhash");
        assertThat(verifier.verifyAny(List.of(
                anchor.record("chain-1"), tampered))).isTrue();
    }

    @Test
    void verifyConsistentFailsOnTampered() {
        anchor.anchor("chain-1", "block-1", 1000, "head");
        AnchorRecord tampered = new AnchorRecord("chain-2",
                "block-2", 1000, "head", "badhash");
        assertThat(verifier.verifyConsistent(List.of(
                anchor.record("chain-1"), tampered))).isFalse();
    }

    @Test
    void verifyConsistentFailsOnHeadMismatch() {
        anchor.anchor("chain-1", "block-1", 1000, "head1");
        anchor.anchor("chain-2", "block-2", 1000, "head2");
        assertThat(verifier.verifyConsistent(List.of(
                anchor.record("chain-1"),
                anchor.record("chain-2")))).isFalse();
    }

    @Test
    void verifyAnyEmptyFalse() {
        assertThat(verifier.verifyAny(List.of())).isFalse();
        assertThat(verifier.verifyConsistent(List.of())).isFalse();
    }

    @Test
    void unknownChainRejected() {
        assertThatThrownBy(() -> anchor.record("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyChainIdsRejected() {
        assertThatThrownBy(() -> anchor.anchorAll(
                Set.of(), 1000, "head"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chainIdsTracked() {
        anchor.anchorAll(Set.of("a", "b", "c"), 1, "head");
        assertThat(anchor.chainIds()).containsExactlyInAnyOrder(
                "a", "b", "c");
        assertThat(anchor.size()).isEqualTo(3);
    }

    @ParameterizedTest(name = "chain {0}")
    @ValueSource(strings = {"ethereum", "solana", "polygon",
            "local", "anchor"})
    void parameterizedChains(String chainId) {
        AnchorRecord record = anchor.anchor(chainId,
                "block-1", 1000, "head");
        assertThat(verifier.verifyAny(
                List.of(record))).isTrue();
    }

    @ParameterizedTest(name = "chains {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedChainCounts(int count) {
        Set<String> chainIds = new java.util.LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            chainIds.add("chain-" + i);
        }
        Map<String, AnchorRecord> records = anchor.anchorAll(
                chainIds, 1000, "head");
        assertThat(verifier.verifyConsistent(
                records.values())).isTrue();
    }

    @Test
    void concurrentAnchorStable() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    anchor.anchor("chain-" + i,
                            "block-" + i, i, "head");
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(anchor.size()).isEqualTo(50);
    }

    @Test
    void headConsistencyAcrossChains() {
        Map<String, AnchorRecord> records = anchor.anchorAll(
                Set.of("a", "b"), 1, "same-head");
        assertThat(verifier.verifyConsistent(
                records.values())).isTrue();
    }
}
