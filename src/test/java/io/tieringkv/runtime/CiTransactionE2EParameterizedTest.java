package io.tieringkv.runtime;

import io.tieringkv.mvcc.Transaction;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** CI 容器运行时 E2E 参数化（TD-048）：规模、回滚、故障路径矩阵。 */
class CiTransactionE2EParameterizedTest {

    private Path dir;
    private Phase24E2EFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        dir = java.nio.file.Files.createTempDirectory("phase24-e2e-param");
        fixture = Phase24E2EFixture.start(dir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void parameterizedSingleRegionTxns(int txnCount) {
        for (int i = 0; i < txnCount; i++) {
            Transaction txn = fixture.router().begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            fixture.router().commit(txn);
        }
        assertThat(fixture.engineA().latestValue(bytes("a" + (txnCount - 1))))
                .isEqualTo(bytes("va" + (txnCount - 1)));
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 5, 10})
    void parameterizedCrossRegionTxns(int txnCount) {
        for (int i = 0; i < txnCount; i++) {
            Transaction txn = fixture.router().begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            txn.put(bytes("b" + i), bytes("vb" + i));
            fixture.router().commit(txn);
        }
        assertThat(fixture.engineA().latestValue(bytes("a" + (txnCount - 1))))
                .isEqualTo(bytes("va" + (txnCount - 1)));
        assertThat(fixture.engineB().latestValue(bytes("b" + (txnCount - 1))))
                .isEqualTo(bytes("vb" + (txnCount - 1)));
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 10, 50})
    void parameterizedMsetSize(int keyCount) {
        Transaction txn = fixture.router().begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("a" + i), bytes("v" + i));
        }
        fixture.router().commit(txn);
        for (int i = 0; i < keyCount; i++) {
            TxnMessages.Response response =
                    fixture.regionA().get(bytes("a" + i)).join();
            assertThat(response.message()).isEqualTo("v" + i);
        }
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedRollback(int keyCount) {
        Transaction txn = fixture.router().begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("a" + i), bytes("v" + i));
        }
        fixture.router().rollback(txn);
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.engineA().latestValue(bytes("a" + i))).isNull();
        }
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 3})
    void parameterizedKillParticipantB(int txnCount) throws Exception {
        for (int i = 0; i < txnCount; i++) {
            Transaction txn = fixture.router().begin();
            txn.put(bytes("b" + i), bytes("vb" + i));
            fixture.router().commit(txn);
        }
        fixture.restartParticipantB();
        Transaction after = fixture.router().begin();
        after.put(bytes("b" + txnCount), bytes("vb" + txnCount));
        fixture.router().commit(after);
        assertThat(fixture.engineB().latestValue(bytes("b" + txnCount)))
                .isEqualTo(bytes("vb" + txnCount));
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {5, 10})
    void parameterizedNetworkPartition(int txnCount) {
        for (int i = 0; i < txnCount; i++) {
            Transaction txn = fixture.router().begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            try {
                fixture.router().commit(txn);
            } catch (RuntimeException ignored) {
                // 瞬时故障
            }
        }
        fixture.router().recover();
        assertThat(fixture.engineA().latestValue(bytes("a" + (txnCount - 1))))
                .isEqualTo(bytes("va" + (txnCount - 1)));
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 2})
    void parameterizedKillCoordinator(int txnCount) throws Exception {
        for (int i = 0; i < txnCount; i++) {
            Transaction txn = fixture.router().begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            fixture.router().commit(txn);
        }
        io.tieringkv.transaction.router.DistributedTxnRouter restarted =
                fixture.restartCoordinator();
        Transaction after = restarted.begin();
        after.put(bytes("a" + txnCount), bytes("va" + txnCount));
        try {
            restarted.commit(after);
        } catch (RuntimeException ignored) {
            // 瞬时故障
        }
        restarted.recover();
        assertThat(fixture.engineA().latestValue(bytes("a" + txnCount)))
                .isEqualTo(bytes("va" + txnCount));
    }

    @Test
    void getMissingKeyReturnsNullMessage() {
        TxnMessages.Response response = fixture.regionA().get(bytes("missing"))
                .join();
        assertThat(response.message()).isEmpty();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {2, 5, 10})
    void parameterizedCrossRegionKeyCounts(int keyCount) {
        Transaction txn = fixture.router().begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("a" + i), bytes("va" + i));
            txn.put(bytes("b" + i), bytes("vb" + i));
        }
        fixture.router().commit(txn);
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.engineA().latestValue(bytes("a" + i)))
                    .isEqualTo(bytes("va" + i));
            assertThat(fixture.engineB().latestValue(bytes("b" + i)))
                    .isEqualTo(bytes("vb" + i));
        }
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 10})
    void parameterizedGetAfterCommit(int keyCount) {
        Transaction txn = fixture.router().begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("a" + i), bytes("v" + i));
        }
        fixture.router().commit(txn);
        for (int i = 0; i < keyCount; i++) {
            TxnMessages.Response response = fixture.regionA()
                    .get(bytes("a" + i)).join();
            assertThat(response.message()).isEqualTo("v" + i);
        }
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {2, 5})
    void parameterizedKillParticipantThenMset(int keyCount) throws Exception {
        fixture.restartParticipantB();
        Transaction txn = fixture.router().begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("b" + i), bytes("vb" + i));
        }
        fixture.router().commit(txn);
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.engineB().latestValue(bytes("b" + i)))
                    .isEqualTo(bytes("vb" + i));
        }
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 2})
    void parameterizedRollbackAfterKill(int txnCount) throws Exception {
        fixture.restartParticipantB();
        for (int i = 0; i < txnCount; i++) {
            Transaction txn = fixture.router().begin();
            txn.put(bytes("b" + i), bytes("vb" + i));
            fixture.router().rollback(txn);
        }
        for (int i = 0; i < txnCount; i++) {
            assertThat(fixture.engineB().latestValue(bytes("b" + i)))
                    .isNull();
        }
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {2, 3})
    void parameterizedPartitionThenRecoverTwice(int rounds) {
        for (int round = 0; round < rounds; round++) {
            Transaction txn = fixture.router().begin();
            txn.put(bytes("a" + round), bytes("va" + round));
            try {
                fixture.router().commit(txn);
            } catch (RuntimeException ignored) {
                // 瞬时故障
            }
            fixture.router().recover();
        }
        assertThat(fixture.engineA().latestValue(bytes("a" + (rounds - 1))))
                .isEqualTo(bytes("va" + (rounds - 1)));
    }

    @Test
    void emptyTxnCommit() {
        fixture.router().commit(fixture.router().begin());
        assertThat(fixture.router().recover().committed())
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    void deleteTxnRemovesValue() {
        Transaction seed = fixture.router().begin();
        seed.put(bytes("a1"), bytes("v1"));
        fixture.router().commit(seed);
        Transaction delete = fixture.router().begin();
        delete.delete(bytes("a1"));
        fixture.router().commit(delete);
        assertThat(fixture.engineA().latestValue(bytes("a1"))).isNull();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 3})
    void parameterizedOverwriteValues(int rounds) {
        for (int i = 0; i < rounds; i++) {
            Transaction txn = fixture.router().begin();
            txn.put(bytes("a1"), bytes("v" + i));
            fixture.router().commit(txn);
        }
        assertThat(fixture.engineA().latestValue(bytes("a1")))
                .isEqualTo(bytes("v" + (rounds - 1)));
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {5, 20})
    void parameterizedSingleRegionGet(int keyCount) {
        Transaction txn = fixture.router().begin();
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("a" + i), bytes("v" + i));
        }
        fixture.router().commit(txn);
        for (int i = 0; i < keyCount; i++) {
            assertThat(fixture.regionA().get(bytes("a" + i)).join()
                    .message()).isEqualTo("v" + i);
        }
    }

    @Test
    void crossRegionOverwrite() {
        Transaction first = fixture.router().begin();
        first.put(bytes("a1"), bytes("va1"));
        first.put(bytes("b1"), bytes("vb1"));
        fixture.router().commit(first);
        Transaction second = fixture.router().begin();
        second.put(bytes("a1"), bytes("va2"));
        second.put(bytes("b1"), bytes("vb2"));
        fixture.router().commit(second);
        assertThat(fixture.engineA().latestValue(bytes("a1")))
                .isEqualTo(bytes("va2"));
        assertThat(fixture.engineB().latestValue(bytes("b1")))
                .isEqualTo(bytes("vb2"));
    }

    @Test
    void rollbackThenCommitSameKey() {
        Transaction rollback = fixture.router().begin();
        rollback.put(bytes("a1"), bytes("v1"));
        fixture.router().rollback(rollback);
        assertThat(fixture.engineA().latestValue(bytes("a1"))).isNull();
        Transaction commit = fixture.router().begin();
        commit.put(bytes("a1"), bytes("v2"));
        fixture.router().commit(commit);
        assertThat(fixture.engineA().latestValue(bytes("a1")))
                .isEqualTo(bytes("v2"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
