package io.tieringkv.transaction.pessimistic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 悲观事务（ADR-0208）：加锁 + 可见性 + 超时。 */
class PessimisticTransactionTest {

    @Test
    void beginAndLock() {
        PessimisticTransaction txn = txn();
        txn.begin("t1");
        assertThat(txn.lock("k1", "t1", 100, 0)).isTrue();
        assertThat(txn.isOpen()).isTrue();
    }

    @Test
    void conflictingOwnerRejected() {
        PessimisticTransaction txn = txn();
        txn.begin("t1");
        txn.lock("k1", "t1", 100, 0);
        assertThat(txn.lock("k1", "t2", 100, 0)).isFalse();
        assertThat(txn.isLocked("k1", "t2")).isTrue();
    }

    @Test
    void sameOwnerReentrant() {
        PessimisticTransaction txn = txn();
        txn.begin("t1");
        assertThat(txn.lock("k1", "t1", 100, 0)).isTrue();
        assertThat(txn.lock("k1", "t1", 200, 100)).isTrue();
    }

    @Test
    void lockTimeout() {
        PessimisticTransaction txn = txn();
        txn.begin("t1");
        assertThatThrownBy(() -> txn.lock("k1", "t1",
                1000, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void writeReadVisibility() {
        PessimisticTransaction txn = txn();
        txn.begin("t1");
        txn.write("k1", bytes("v1"));
        assertThat(txn.read("k1")).isEqualTo(bytes("v1"));
    }

    @Test
    void commitClearsLocks() {
        PessimisticTransaction txn = txn();
        txn.begin("t1");
        txn.lock("k1", "t1", 100, 0);
        txn.commit();
        assertThat(txn.isOpen()).isFalse();
        assertThat(txn.lockedKeys()).isEmpty();
    }

    @Test
    void rollbackClearsState() {
        PessimisticTransaction txn = txn();
        txn.begin("t1");
        txn.write("k1", bytes("v"));
        txn.rollback();
        assertThat(txn.isOpen()).isFalse();
        assertThat(txn.lockedKeys()).isEmpty();
    }

    @Test
    void doubleBeginRejected() {
        PessimisticTransaction txn = txn();
        txn.begin("t1");
        assertThatThrownBy(() -> txn.begin("t2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void operationsWithoutBeginRejected() {
        PessimisticTransaction txn = txn();
        assertThatThrownBy(() -> txn.write("k", bytes("v")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(txn::commit)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankTxnIdRejected() {
        PessimisticTransaction txn = txn();
        assertThatThrownBy(() -> txn.begin(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTimeoutRejected() {
        assertThatThrownBy(() -> new PessimisticTransaction(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "timeout {0}")
    @ValueSource(longs = {1, 100, 1000})
    void parameterizedTimeouts(long timeout) {
        PessimisticTransaction txn = new PessimisticTransaction(
                timeout);
        txn.begin("t1");
        assertThat(txn.lock("k1", "t1", timeout, 0)).isTrue();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedLockCounts(int count) {
        PessimisticTransaction txn = txn();
        txn.begin("t1");
        for (int i = 0; i < count; i++) {
            assertThat(txn.lock("k" + i, "t1", 100, 0)).isTrue();
        }
        assertThat(txn.lockedKeys()).hasSize(count);
    }

    @Test
    void concurrentLocksStable() throws Exception {
        PessimisticTransaction txn = txn();
        txn.begin("t1");
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    txn.lock("k" + i, "t1", 100, 0);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(txn.lockedKeys()).hasSize(50);
    }

    @Test
    void txnIdTracked() {
        PessimisticTransaction txn = txn();
        txn.begin("txn-42");
        assertThat(txn.txnId()).isEqualTo("txn-42");
    }

    private static PessimisticTransaction txn() {
        return new PessimisticTransaction(500);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
