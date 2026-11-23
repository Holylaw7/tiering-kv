package io.tieringkv.memory;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPoolTest {

    @Test
    void allocateReleaseReuse() {
        MemoryPool pool = new MemoryPool();
        ByteBuffer first = pool.allocateRaw(100);
        assertThat(first.isDirect()).isTrue();
        pool.release(first);
        ByteBuffer second = pool.allocateRaw(100);
        assertThat(second).isSameAs(first); // 复用
        pool.release(second);
        AllocationTracker.Snapshot snapshot = pool.tracker().snapshot();
        assertThat(snapshot.reuseCount()).isEqualTo(1);
        assertThat(snapshot.allocatedBytes()).isEqualTo(4096);
        assertThat(snapshot.releasedBytes()).isEqualTo(8192); // 复用缓冲释放两次
    }

    @Test
    void peakTracksLiveBytes() {
        MemoryPool pool = new MemoryPool();
        ByteBuffer a = pool.allocateRaw(100);
        ByteBuffer b = pool.allocateRaw(100);
        assertThat(pool.tracker().snapshot().peakBytes()).isEqualTo(4096L * 2);
        pool.release(a);
        pool.release(b);
        assertThat(pool.tracker().snapshot().liveBytes()).isZero();
    }

    @Test
    void oversizedAllocationIsNotPooled() {
        MemoryPool pool = new MemoryPool();
        ByteBuffer big = pool.allocateRaw(1 << 20);
        pool.release(big);
        AllocationTracker.Snapshot snapshot = pool.tracker().snapshot();
        assertThat(snapshot.allocatedBytes()).isEqualTo(1 << 20);
        assertThat(snapshot.reuseCount()).isZero();
    }
}
