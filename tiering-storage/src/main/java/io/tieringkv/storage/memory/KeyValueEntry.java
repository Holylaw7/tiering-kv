package io.tieringkv.storage.memory;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 键值条目（ADR-0007 / B2 优化）。字段覆盖 WAL / Snapshot / LSM Flush / Compaction 需要：
 * 创建/更新时间、过期时间、全局版本、tombstone 标记、字节估算以及原地内联的
 * 24-bit Redis/Valkey 风格 Morris-LFU 状态（16-bit 衰减时间槽 + 8-bit 对数频次）。
 *
 * <p>由于 JVM 压缩指针与 8 字节对象对齐填充，新增 4 字节 {@code lfuState} 处于原有尾部
 * 填充间隙内，对象实例大小不变（仍为 64 B），彻底免除外部 LFU 影子 Map/TreeSet 内存开销。
 */
public final class KeyValueEntry {

    /** 固定开销估算：对象头 + 数组头 + 时间戳/版本字段（约数，不追求精确）。 */
    private static final int OVERHEAD_BYTES = 64;

    /** Redis/Valkey 近似 LFU 默认参数：初始值 5、上限 255、对数因子 10。 */
    public static final int LFU_INIT_VAL = 5;
    public static final int LFU_MAX_VAL = 255;
    private static final int LFU_LOG_FACTOR = 10;
    private static final long DEFAULT_LFU_DECAY_MILLIS = 60_000L;

    private final byte[] key;
    private final byte[] value;
    private final long createTimestamp;
    private final long updateTimestamp;
    private final long expireTimestamp;
    private final long version;
    private final boolean deleted;
    private final int size;
    /** 高 16 位：时间槽（nowMillis / decayInterval）；低 8 位：Morris 对数频次 [0..255]。 */
    private volatile int lfuState;

    public KeyValueEntry(byte[] key, byte[] value,
                         long createTimestamp, long updateTimestamp,
                         long expireTimestamp, long version,
                         boolean deleted, int size) {
        key = key.clone();
        if (value != null) {
            value = value.clone();
        }
        this.key = key;
        this.value = value;
        this.createTimestamp = createTimestamp;
        this.updateTimestamp = updateTimestamp;
        this.expireTimestamp = expireTimestamp;
        this.version = version;
        this.deleted = deleted;
        this.size = size;
        this.lfuState = encodeLfu(timeSlotOf(updateTimestamp, DEFAULT_LFU_DECAY_MILLIS), LFU_INIT_VAL);
    }

    /** 所有权转移构造（ADR-0059 / B2）：不克隆；调用方保证转移后不再修改数组。 */
    private KeyValueEntry(byte[] key, byte[] value,
                          long createTimestamp, long updateTimestamp,
                          long expireTimestamp, long version,
                          boolean deleted, int size, long lfuDecayMillis) {
        this.key = key;
        this.value = value;
        this.createTimestamp = createTimestamp;
        this.updateTimestamp = updateTimestamp;
        this.expireTimestamp = expireTimestamp;
        this.version = version;
        this.deleted = deleted;
        this.size = size;
        this.lfuState = encodeLfu(timeSlotOf(updateTimestamp, lfuDecayMillis), LFU_INIT_VAL);
    }

    /** 新建存活 entry；ttlMillis &lt;= 0 表示永不过期。 */
    public static KeyValueEntry live(byte[] key, byte[] value, long nowMillis, long ttlMillis, long version) {
        long expireTimestamp = ttlMillis > 0 ? nowMillis + ttlMillis : -1;
        return new KeyValueEntry(key, value, nowMillis, nowMillis, expireTimestamp, version, false,
                sizeOf(key, value));
    }

    /** 所有权转移版本（ADR-0059 / B2）：热写与 applyRawBatch 零额外克隆使用。 */
    public static KeyValueEntry liveOwned(byte[] key, byte[] value, long nowMillis,
                                          long ttlMillis, long version) {
        return liveOwned(key, value, nowMillis, ttlMillis, version, DEFAULT_LFU_DECAY_MILLIS);
    }

    static KeyValueEntry liveOwned(byte[] key, byte[] value, long nowMillis,
                                   long ttlMillis, long version, long lfuDecayMillis) {
        long expireTimestamp = ttlMillis > 0 ? nowMillis + ttlMillis : -1;
        return new KeyValueEntry(key, value, nowMillis, nowMillis,
                expireTimestamp, version, false, sizeOf(key, value), lfuDecayMillis);
    }

    /** tombstone：保留键位但不物理删除（为 WAL / Snapshot / LSM 准备）。 */
    public static KeyValueEntry tombstone(byte[] key, long nowMillis, long version) {
        return new KeyValueEntry(key, null, nowMillis, nowMillis, -1, version, true, sizeOf(key, null));
    }

    /**
     * 无锁原位更新近似 LFU 热度（Redis/Valkey Morris Counter 算法）：
     * 仅在时间槽推进或概率计数实际递增时写回 {@code lfuState}，99%+ 热读保持共享缓存行零脏写。
     */
    public void touchLfu(long nowMillis, long decayIntervalMillis) {
        int state = this.lfuState;
        int nowSlot = timeSlotOf(nowMillis, decayIntervalMillis);
        int lastSlot = (state >>> 8) & 0xFFFF;
        int counter = state & 0xFF;
        if (counter == LFU_MAX_VAL && nowSlot == lastSlot) {
            return;
        }
        int decayed = decayCounter(counter, lastSlot, nowSlot);
        if (decayed < LFU_MAX_VAL) {
            int baseVal = Math.max(0, decayed - LFU_INIT_VAL);
            double probability = 1.0 / (baseVal * (double) LFU_LOG_FACTOR + 1.0);
            if (ThreadLocalRandom.current().nextDouble() < probability) {
                decayed++;
            }
        }
        int nextState = encodeLfu(nowSlot, decayed);
        if (nextState != state) {
            this.lfuState = nextState;
        }
    }

    /** 按指定衰减窗口重新初始化时间槽（与 MemTable 自定义 decayIntervalMillis 对齐）。 */
    void initLfuSlot(long nowMillis, long decayIntervalMillis) {
        this.lfuState = encodeLfu(timeSlotOf(nowMillis, decayIntervalMillis), LFU_INIT_VAL);
    }

    /** 计算惰性衰减后的当前有效 LFU 频次 [0..255]（只读无写入）。 */
    public int effectiveLfu(long nowMillis, long decayIntervalMillis) {
        int state = this.lfuState;
        int nowSlot = timeSlotOf(nowMillis, decayIntervalMillis);
        int lastSlot = (state >>> 8) & 0xFFFF;
        int counter = state & 0xFF;
        return decayCounter(counter, lastSlot, nowSlot);
    }

    public int lfuCounter() {
        return this.lfuState & 0xFF;
    }

    private static int encodeLfu(int slot16, int counter8) {
        return ((slot16 & 0xFFFF) << 8) | (counter8 & 0xFF);
    }

    private static int timeSlotOf(long nowMillis, long decayIntervalMillis) {
        long step = Math.max(1L, decayIntervalMillis);
        return (int) (Math.floorDiv(nowMillis, step) & 0xFFFFL);
    }

    private static int decayCounter(int counter, int lastSlot, int nowSlot) {
        if (nowSlot == lastSlot || counter == 0) {
            return counter;
        }
        int elapsed = nowSlot >= lastSlot
                ? (nowSlot - lastSlot)
                : ((0xFFFF - lastSlot) + nowSlot + 1);
        // 防御测试时钟倒退或跨越半周期导致的误衰减：超出半周期视为无正向推进
        if (elapsed > 0x8000) {
            return counter;
        }
        return Math.max(0, counter - elapsed);
    }

    public byte[] key() {
        return key;
    }

    public byte[] value() {
        return value;
    }

    public long createTimestamp() {
        return createTimestamp;
    }

    public long updateTimestamp() {
        return updateTimestamp;
    }

    public long expireTimestamp() {
        return expireTimestamp;
    }

    public long version() {
        return version;
    }

    public boolean deleted() {
        return deleted;
    }

    public int size() {
        return size;
    }

    /** 存活 = 未删除且（无过期时间或未到过期时间）。 */
    public boolean isLive(long nowMillis) {
        return !deleted && (expireTimestamp < 0 || nowMillis < expireTimestamp);
    }

    public boolean isExpired(long nowMillis) {
        return expireTimestamp >= 0 && nowMillis >= expireTimestamp;
    }

    public static int sizeOf(byte[] key, byte[] value) {
        return key.length + (value == null ? 0 : value.length) + OVERHEAD_BYTES;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KeyValueEntry that
                && Arrays.equals(key, that.key)
                && Arrays.equals(value, that.value)
                && createTimestamp == that.createTimestamp
                && updateTimestamp == that.updateTimestamp
                && expireTimestamp == that.expireTimestamp
                && version == that.version
                && deleted == that.deleted
                && size == that.size;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        result = 31 * result + Long.hashCode(createTimestamp);
        result = 31 * result + Long.hashCode(updateTimestamp);
        result = 31 * result + Long.hashCode(expireTimestamp);
        result = 31 * result + Long.hashCode(version);
        result = 31 * result + Boolean.hashCode(deleted);
        result = 31 * result + size;
        return result;
    }

    @Override
    public String toString() {
        return "KeyValueEntry[key=" + Arrays.toString(key)
                + ", value=" + Arrays.toString(value)
                + ", createTimestamp=" + createTimestamp
                + ", updateTimestamp=" + updateTimestamp
                + ", expireTimestamp=" + expireTimestamp
                + ", version=" + version
                + ", deleted=" + deleted
                + ", size=" + size + "]";
    }
}
