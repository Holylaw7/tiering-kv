package io.tieringkv.storage.cache;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.TimeSource;

import java.util.List;
import java.util.function.Predicate;

/**
 * Redis/Valkey 风格近似 LFU 采样淘汰策略（B2 生产默认）。
 *
 * <p>摒弃全局锁 {@code TreeSet} 与每键 ~350 B 的影子索引对象图：热度元数据
 * （16-bit 衰减时间槽 + 8-bit Morris 对数频次）直接内联在 {@link KeyValueEntry}
 * 原有对象对齐间隙中，热路径 {@code GET/SET} 零锁零分配；仅在内存超限触发淘汰时
 * 对 {@link MemTable} 随机抽样 {@code sampleSize}（默认 16）个存活条目，择取衰减后
 * 热度最低者迁冷。
 */
public final class SampledLfuPolicy implements EvictionPolicy {

    public static final int DEFAULT_SAMPLE_SIZE = 16;

    private final MemTable memTable;
    private final long decayIntervalMillis;
    private final int sampleSize;
    private final TimeSource timeSource;
    private final Predicate<byte[]> candidateFilter;

    public SampledLfuPolicy(MemTable memTable, long decayIntervalMillis) {
        this(memTable, decayIntervalMillis, DEFAULT_SAMPLE_SIZE, System::currentTimeMillis, null);
    }

    public SampledLfuPolicy(MemTable memTable, long decayIntervalMillis, int sampleSize) {
        this(memTable, decayIntervalMillis, sampleSize, System::currentTimeMillis, null);
    }

    public SampledLfuPolicy(MemTable memTable,
                            long decayIntervalMillis,
                            int sampleSize,
                            TimeSource timeSource,
                            Predicate<byte[]> candidateFilter) {
        if (memTable == null) {
            throw new IllegalArgumentException("memTable required");
        }
        if (decayIntervalMillis <= 0) {
            throw new IllegalArgumentException("decayIntervalMillis must be positive");
        }
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sampleSize must be positive");
        }
        this.memTable = memTable;
        this.decayIntervalMillis = decayIntervalMillis;
        this.sampleSize = sampleSize;
        this.timeSource = timeSource != null ? timeSource : System::currentTimeMillis;
        this.candidateFilter = candidateFilter;
        this.memTable.setLfuDecayIntervalMillis(decayIntervalMillis);
    }

    @Override
    public String name() {
        return "sampled-lfu";
    }

    @Override
    public boolean tracksViaAccessEvents() {
        return false;
    }

    @Override
    public void onAccess(AccessEvent event) {
        // 热度已由 MemTable 在 KeyValueEntry 原地无锁维护，热路径零开销
    }

    @Override
    public EvictionCandidate selectCandidate() {
        long now = timeSource.nowMillis();
        List<KeyValueEntry> samples = memTable.sampleLiveEntries(sampleSize, now);
        if (samples.isEmpty()) {
            return null;
        }
        KeyValueEntry coldest = null;
        int coldestFreq = Integer.MAX_VALUE;
        for (KeyValueEntry entry : samples) {
            if (candidateFilter != null && !candidateFilter.test(entry.key())) {
                continue;
            }
            int freq = entry.effectiveLfu(now, decayIntervalMillis);
            if (coldest == null
                    || freq < coldestFreq
                    || (freq == coldestFreq && entry.updateTimestamp() < coldest.updateTimestamp())
                    || (freq == coldestFreq
                        && entry.updateTimestamp() == coldest.updateTimestamp()
                        && entry.version() < coldest.version())) {
                coldest = entry;
                coldestFreq = freq;
            }
        }
        if (coldest == null) {
            return null;
        }
        return new EvictionCandidate(
                coldest.key(),
                coldestFreq,
                coldest.updateTimestamp(),
                coldest.size(),
                coldestFreq);
    }

    public int sampleSize() {
        return sampleSize;
    }

    public long decayIntervalMillis() {
        return decayIntervalMillis;
    }
}
