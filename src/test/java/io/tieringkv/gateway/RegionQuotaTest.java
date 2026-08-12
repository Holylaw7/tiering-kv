package io.tieringkv.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 地域写入配额（ADR-0149）：配额矩阵 + 周期重置。 */
class RegionQuotaTest {

    @Test
    void acquireWithinQuota() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 3);
        assertThat(quota.tryAcquire("r1")).isTrue();
        assertThat(quota.tryAcquire("r1")).isTrue();
        assertThat(quota.tryAcquire("r1")).isTrue();
        assertThat(quota.tryAcquire("r1")).isFalse();
        assertThat(quota.remaining("r1")).isZero();
    }

    @Test
    void regionsIsolated() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 1);
        quota.setQuota("r2", 1);
        assertThat(quota.tryAcquire("r1")).isTrue();
        assertThat(quota.tryAcquire("r2")).isTrue();
        assertThat(quota.remaining("r1")).isZero();
        assertThat(quota.remaining("r2")).isZero();
    }

    @Test
    void unknownRegionRejected() {
        RegionQuota quota = new RegionQuota();
        assertThatThrownBy(() -> quota.tryAcquire("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetCycleRestoresCapacity() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 1);
        quota.tryAcquire("r1");
        assertThat(quota.remaining("r1")).isZero();
        quota.resetCycle();
        assertThat(quota.remaining("r1")).isEqualTo(1);
        assertThat(quota.tryAcquire("r1")).isTrue();
    }

    @Test
    void negativeQuotaRejected() {
        RegionQuota quota = new RegionQuota();
        assertThatThrownBy(() -> quota.setQuota("r1", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quotaOverwriteApplies() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 1);
        quota.tryAcquire("r1");
        quota.setQuota("r1", 5);
        assertThat(quota.quota("r1")).isEqualTo(5);
        assertThat(quota.tryAcquire("r1")).isTrue();
    }

    @Test
    void usedTracksAcquisitions() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 10);
        quota.tryAcquire("r1");
        quota.tryAcquire("r1");
        quota.tryAcquire("r1");
        assertThat(quota.used("r1")).isEqualTo(3);
    }

    @Test
    void concurrentAcquireExactLimit() throws Exception {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 1000);
        AtomicInteger accepted = new AtomicInteger();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    if (quota.tryAcquire("r1")) {
                        accepted.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(accepted.get()).isEqualTo(1000);
        assertThat(quota.used("r1")).isEqualTo(1000);
    }

    @ParameterizedTest(name = "quota {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedQuotaSizes(int limit) {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", limit);
        for (int i = 0; i < limit; i++) {
            assertThat(quota.tryAcquire("r1")).isTrue();
        }
        assertThat(quota.tryAcquire("r1")).isFalse();
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(strings = {"2", "3"})
    void parameterizedRegionCounts(int count) {
        RegionQuota quota = new RegionQuota();
        for (int i = 0; i < count; i++) {
            quota.setQuota("r" + i, 5);
        }
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < 5; j++) {
                assertThat(quota.tryAcquire("r" + i)).isTrue();
            }
            assertThat(quota.tryAcquire("r" + i)).isFalse();
        }
    }

    @Test
    void zeroQuotaRejectsAll() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 0);
        assertThat(quota.tryAcquire("r1")).isFalse();
        assertThat(quota.remaining("r1")).isZero();
    }
}
