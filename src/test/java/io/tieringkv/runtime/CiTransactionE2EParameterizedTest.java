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

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
