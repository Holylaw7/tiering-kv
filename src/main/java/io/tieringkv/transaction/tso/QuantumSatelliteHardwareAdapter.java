package io.tieringkv.transaction.tso;

/**
 * 量子/卫星授时硬件适配（ADR-0251）：硬件接口 + 模拟实现 +
 * 校准 + 单调 + 防回拨。
 */
public final class QuantumSatelliteHardwareAdapter {

    /** 硬件接口。 */
    public interface HardwareClock {
        long readTimeMillis();

        boolean healthy();
    }

    /** 模拟硬件：确定性 + 可注入漂移/故障。 */
    public static final class SimulatedHardwareClock
            implements HardwareClock {
        private final long baseTime;
        private final long driftMillis;
        private volatile boolean failed;

        public SimulatedHardwareClock(long baseTime,
                                      long driftMillis) {
            this.baseTime = baseTime;
            this.driftMillis = driftMillis;
        }

        @Override
        public long readTimeMillis() {
            return baseTime + driftMillis;
        }

        @Override
        public boolean healthy() {
            return !failed;
        }

        public void fail() {
            failed = true;
        }

        public void recover() {
            failed = false;
        }
    }

    private final HardwareClock hardware;
    private final long propagationDelayMillis;
    private long lastTimestamp = Long.MIN_VALUE;
    private long readings;
    private long failures;

    public QuantumSatelliteHardwareAdapter(
            HardwareClock hardware,
            long propagationDelayMillis) {
        if (hardware == null || propagationDelayMillis < 0) {
            throw new IllegalArgumentException(
                    "hardware required and delay must be "
                            + "non-negative");
        }
        this.hardware = hardware;
        this.propagationDelayMillis = propagationDelayMillis;
    }

    /** 读取 + 校正 + 单调推进（硬件故障降级为上次值）。 */
    public synchronized long timestamp() {
        if (!hardware.healthy()) {
            failures++;
            return lastTimestamp == Long.MIN_VALUE
                    ? 0 : lastTimestamp;
        }
        readings++;
        long corrected = hardware.readTimeMillis()
                + propagationDelayMillis;
        long candidate = lastTimestamp == Long.MIN_VALUE
                ? corrected
                : Math.max(corrected, lastTimestamp + 1);
        lastTimestamp = candidate;
        return candidate;
    }

    public long restore(long persistedWatermark) {
        if (persistedWatermark < 0) {
            throw new IllegalArgumentException(
                    "watermark must be non-negative");
        }
        lastTimestamp = Math.max(lastTimestamp,
                persistedWatermark);
        return persistedWatermark;
    }

    public boolean healthy() {
        return hardware.healthy();
    }

    public long readings() {
        return readings;
    }

    public long failures() {
        return failures;
    }
}
