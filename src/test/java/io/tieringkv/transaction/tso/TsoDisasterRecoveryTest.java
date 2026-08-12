package io.tieringkv.transaction.tso;

import io.tieringkv.transaction.tso.TsoDisasterRecovery.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TSO 跨地域容灾（ADR-0223）：主备 + 切换 + 恢复不回退。 */
class TsoDisasterRecoveryTest {

    @Test
    void allocateAdvancesPrimaryAndSyncedWatermark() {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        long[] range = recovery.allocate(10);
        assertThat(range[1]).isEqualTo(9);
        assertThat(recovery.syncedWatermark()).isEqualTo(9);
        assertThat(recovery.primaryWatermark()).isEqualTo(9);
    }

    @Test
    void failoverSwitchesToStandby() {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        recovery.allocate(10);
        long restored = recovery.failover();
        assertThat(restored).isEqualTo(9);
        assertThat(recovery.state())
                .isEqualTo(State.STANDBY_ACTIVE);
        assertThat(recovery.primaryActive()).isFalse();
    }

    @Test
    void failoverAllocationAboveSyncedWatermark() {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        recovery.allocate(10);
        recovery.failover();
        long ts = recovery.allocate();
        assertThat(ts).isEqualTo(10);
    }

    @Test
    void recoverPrimaryRestoresFromStandby() {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        recovery.allocate(10);
        recovery.failover();
        recovery.allocate(5);
        long restored = recovery.recoverPrimary();
        assertThat(restored).isEqualTo(14);
        assertThat(recovery.state())
                .isEqualTo(State.PRIMARY_ACTIVE);
        assertThat(recovery.allocate()).isEqualTo(15);
    }

    @Test
    void failoverCountIncrements() {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        recovery.failover();
        recovery.recoverPrimary();
        recovery.failover();
        assertThat(recovery.failoverCount()).isEqualTo(2);
    }

    @Test
    void nullPrimaryRejected() {
        assertThatThrownBy(() -> new TsoDisasterRecovery(
                null, new TsoService()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void primaryActiveInitially() {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        assertThat(recovery.state())
                .isEqualTo(State.PRIMARY_ACTIVE);
        assertThat(recovery.primaryActive()).isTrue();
    }

    @Test
    void standbyAllocationAfterFailover() {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        recovery.allocate(100);
        recovery.failover();
        long[] range = recovery.allocate(10);
        assertThat(range[0]).isEqualTo(100);
        assertThat(range[1]).isEqualTo(109);
    }

    @Test
    void recoverPrimaryIdempotent() {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        recovery.allocate(10);
        recovery.failover();
        recovery.recoverPrimary();
        assertThat(recovery.recoverPrimary())
                .isEqualTo(recovery.primaryWatermark());
    }

    @Test
    void concurrentAllocationStable() throws Exception {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    recovery.allocate(10);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(recovery.primaryWatermark())
                .isEqualTo(3999);
        assertThat(recovery.syncedWatermark())
                .isEqualTo(3999);
    }

    @ParameterizedTest(name = "batches={0} size={1}")
    @CsvSource({
            "1,1",
            "1,10",
            "2,5",
            "2,10",
            "3,10",
            "3,20",
            "4,25",
            "5,10",
            "5,50",
            "10,10",
            "10,100",
            "20,10",
            "20,50",
            "50,10",
            "50,20",
            "100,1",
            "100,10",
            "100,100",
            "2,1",
            "3,1",
            "4,1",
            "5,1",
            "6,10",
            "7,10",
            "8,10",
            "9,10",
            "10,1",
            "15,2",
            "25,4",
            "40,5"
    })
    void parameterizedFailoverMatrix(int batches, int size) {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        long total = 0;
        for (int i = 0; i < batches; i++) {
            long[] range = recovery.allocate(size);
            total = range[1] + 1;
        }
        recovery.failover();
        long ts = recovery.allocate();
        assertThat(ts).isGreaterThanOrEqualTo(total);
        assertThat(recovery.standbyWatermark())
                .isGreaterThanOrEqualTo(total - 1);
    }

    @ParameterizedTest(name = "batches={0} size={1} extra={2}")
    @CsvSource({
            "1,10,0",
            "1,10,1",
            "2,10,0",
            "2,10,5",
            "3,20,0",
            "3,20,10",
            "5,10,0",
            "5,10,3",
            "10,10,0",
            "10,10,10",
            "10,100,0",
            "10,100,50",
            "20,10,0",
            "20,10,7",
            "50,10,0",
            "50,10,13",
            "100,1,0",
            "100,1,99",
            "5,50,0",
            "5,50,25",
            "3,1,0",
            "3,1,2",
            "7,7,0",
            "7,7,7",
            "13,5,0"
    })
    void parameterizedRecoverMatrix(int batches, int size,
                                    int extra) {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        for (int i = 0; i < batches; i++) {
            recovery.allocate(size);
        }
        recovery.failover();
        for (int i = 0; i < extra; i++) {
            recovery.allocate(1);
        }
        long standbyWatermark = recovery.standbyWatermark();
        recovery.recoverPrimary();
        long ts = recovery.allocate();
        assertThat(ts).isGreaterThan(standbyWatermark);
    }

    @ParameterizedTest(name = "batch {0}")
    @ValueSource(ints = {1, 2, 5, 10, 20, 50, 100, 250, 500,
            1000, 3, 7, 13, 17, 31})
    void parameterizedBatchSizes(int size) {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        long[] range = recovery.allocate(size);
        assertThat(range[1] - range[0])
                .isEqualTo(size - 1L);
        recovery.failover();
        assertThat(recovery.allocate())
                .isEqualTo(range[1] + 1);
    }
}
