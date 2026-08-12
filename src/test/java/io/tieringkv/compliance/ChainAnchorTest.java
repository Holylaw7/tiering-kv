package io.tieringkv.compliance;

import io.tieringkv.compliance.ChainAnchor.AnchorRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 链上锚定（ADR-0188）：锚定 + 验证 + 篡改检测。 */
class ChainAnchorTest {

    private final ChainVerifier verifier = new ChainVerifier();

    @Test
    void anchorCreatesRecord() {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                "block-42", 1000, "abc123");
        assertThat(record.chainId()).isEqualTo("chain-1");
        assertThat(record.blockId()).isEqualTo("block-42");
        assertThat(record.anchorHash()).hasSize(64);
        assertThat(verifier.verify(record)).isTrue();
    }

    @Test
    void tamperedHeadRejected() {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                "block-42", 1000, "abc123");
        AnchorRecord tampered = new AnchorRecord(record.chainId(),
                record.blockId(), record.timestampMillis(),
                "tampered", record.anchorHash());
        assertThat(verifier.verify(tampered)).isFalse();
    }

    @Test
    void tamperedBlockRejected() {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                "block-42", 1000, "abc123");
        AnchorRecord tampered = new AnchorRecord(record.chainId(),
                "block-99", record.timestampMillis(),
                record.headHash(), record.anchorHash());
        assertThat(verifier.verify(tampered)).isFalse();
    }

    @Test
    void expectedHeadMatch() {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                "block-42", 1000, "abc123");
        assertThat(verifier.verify(record, "abc123")).isTrue();
        assertThat(verifier.verify(record, "other")).isFalse();
    }

    @Test
    void nullRecordRejected() {
        assertThatThrownBy(() -> verifier.verify(
                (AnchorRecord) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullHeadRejected() {
        assertThatThrownBy(() -> verifier.verify(
                ChainAnchor.anchor("c", "b", 1, "h"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankChainRejected() {
        assertThatThrownBy(() -> ChainAnchor.anchor("",
                "b", 1, "h"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankBlockRejected() {
        assertThatThrownBy(() -> ChainAnchor.anchor("c",
                "", 1, "h"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankHeadRejected() {
        assertThatThrownBy(() -> ChainAnchor.anchor("c",
                "b", 1, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "chain {0}")
    @ValueSource(strings = {"ethereum", "solana", "polygon",
            "local", "anchor-chain"})
    void parameterizedChains(String chainId) {
        AnchorRecord record = ChainAnchor.anchor(chainId,
                "block-1", 1000, "head");
        assertThat(record.chainId()).isEqualTo(chainId);
        assertThat(verifier.verify(record)).isTrue();
    }

    @ParameterizedTest(name = "block {0}")
    @ValueSource(strings = {"block-0", "block-42", "block-9999",
            "genesis", "latest"})
    void parameterizedBlocks(String blockId) {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                blockId, 1000, "head");
        assertThat(verifier.verify(record)).isTrue();
    }

    @ParameterizedTest(name = "time {0}")
    @ValueSource(longs = {0, 1_000, 1_000_000, 1_700_000_000_000L})
    void parameterizedTimestamps(long timestamp) {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                "block-1", timestamp, "head");
        assertThat(verifier.verify(record)).isTrue();
        assertThat(record.timestampMillis()).isEqualTo(timestamp);
    }

    @ParameterizedTest(name = "head {0}")
    @ValueSource(strings = {"a", "abc123", "hash-1", "sha256hash"})
    void parameterizedHeads(String headHash) {
        AnchorRecord record = ChainAnchor.anchor("chain-1",
                "block-1", 1000, headHash);
        assertThat(verifier.verify(record, headHash)).isTrue();
    }

    @Test
    void anchorHashDeterministic() {
        String first = ChainAnchor.hash("c", "b", 1, "h");
        String second = ChainAnchor.hash("c", "b", 1, "h");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void concurrentAnchorAndVerify() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    AnchorRecord record = ChainAnchor.anchor(
                            "chain-1", "block-" + i, i, "head");
                    assertThat(verifier.verify(record)).isTrue();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }
}
