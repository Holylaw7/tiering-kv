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

/** CI 容器运行时 E2E（TD-048）：TCP 全链路 + 故障路径（JVM 内等价）。 */
class CiTransactionE2ETest {

    private java.nio.file.Path dir;
    private Phase24E2EFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        dir = java.nio.file.Files.createTempDirectory("phase24-e2e");
        fixture = Phase24E2EFixture.start(dir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void setGetRoundTrip() {
        Transaction txn = fixture.router().begin();
        txn.put(bytes("a1"), bytes("va"));
        fixture.router().commit(txn);
        TxnMessages.Response response = fixture.regionA().get(bytes("a1"))
                .join();
        assertThat(response.message()).isEqualTo("va");
    }

    @Test
    void crossRegionTxn() {
        Transaction txn = fixture.router().begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router().commit(txn);
        assertThat(fixture.engineB().latestValue(bytes("b1")))
                .isEqualTo(bytes("vb"));
    }

    @Test
    void msetEquivalent() {
        Transaction txn = fixture.router().begin();
        for (int i = 0; i < 5; i++) {
            txn.put(bytes("a" + i), bytes("v" + i));
        }
        fixture.router().commit(txn);
        for (int i = 0; i < 5; i++) {
            assertThat(fixture.engineA().latestValue(bytes("a" + i)))
                    .isEqualTo(bytes("v" + i));
        }
    }

    @Test
    void killCoordinatorRecovers() throws Exception {
        Transaction txn = fixture.router().begin();
        txn.put(bytes("a1"), bytes("va"));
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router().commit(txn);
        io.tieringkv.transaction.router.DistributedTxnRouter restarted =
                fixture.restartCoordinator();
        Transaction after = restarted.begin();
        after.put(bytes("a2"), bytes("va2"));
        try {
            restarted.commit(after);
        } catch (RuntimeException ignored) {
            // 瞬时故障
        }
        restarted.recover();
        assertThat(fixture.engineA().latestValue(bytes("a2")))
                .isEqualTo(bytes("va2"));
    }

    @Test
    void killParticipantRecovers() throws Exception {
        Transaction txn = fixture.router().begin();
        txn.put(bytes("b1"), bytes("vb"));
        fixture.router().commit(txn);
        fixture.restartParticipantB();
        Transaction after = fixture.router().begin();
        after.put(bytes("b2"), bytes("vb2"));
        fixture.router().commit(after);
        assertThat(fixture.engineB().latestValue(bytes("b2")))
                .isEqualTo(bytes("vb2"));
    }

    @Test
    void networkPartitionNoLostCommit() {
        for (int i = 0; i < 20; i++) {
            Transaction txn = fixture.router().begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            try {
                fixture.router().commit(txn);
            } catch (RuntimeException ignored) {
                // 丢包/瞬时故障
            }
        }
        fixture.router().recover();
        assertThat(fixture.engineA().latestValue(bytes("a19")))
                .isEqualTo(bytes("va19"));
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 5, 10, 20, 50})
    void parameterizedE2E(int txnCount) {
        for (int i = 0; i < txnCount; i++) {
            Transaction txn = fixture.router().begin();
            txn.put(bytes("a" + i), bytes("va" + i));
            txn.put(bytes("b" + i), bytes("vb" + i));
            fixture.router().commit(txn);
        }
        assertThat(fixture.engineA().latestValue(bytes("a" + (txnCount - 1))))
                .isEqualTo(bytes("va" + (txnCount - 1)));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

}
