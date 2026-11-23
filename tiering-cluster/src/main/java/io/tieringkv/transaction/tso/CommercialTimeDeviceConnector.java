package io.tieringkv.transaction.tso;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商用量子/卫星授时设备连接器（ADR-0258）：多厂商驱动 SPI + 连接
 * 生命周期 + 主备切换 + 单调防回拨 + 模拟回退，接入跨云授时仲裁。
 */
public final class CommercialTimeDeviceConnector {

    /** 商用设备驱动 SPI。 */
    public interface TimeDevice {
        String vendor();

        boolean connect();

        long readTimeMillis();

        boolean healthy();

        void disconnect();
    }

    /** 模拟设备：确定性时间 + 可注入漂移/故障（无真实硬件时回退）。 */
    public static final class SimulatedTimeDevice
            implements TimeDevice {
        private final String vendor;
        private final long baseTime;
        private final long driftMillis;
        private volatile boolean connected;
        private volatile boolean failed;

        public SimulatedTimeDevice(String vendor, long baseTime,
                                   long driftMillis) {
            this.vendor = vendor;
            this.baseTime = baseTime;
            this.driftMillis = driftMillis;
        }

        @Override
        public String vendor() {
            return vendor;
        }

        @Override
        public boolean connect() {
            connected = !failed;
            return connected;
        }

        @Override
        public long readTimeMillis() {
            return baseTime + driftMillis;
        }

        @Override
        public boolean healthy() {
            return connected && !failed;
        }

        @Override
        public void disconnect() {
            connected = false;
        }

        public void fail() {
            failed = true;
            connected = false;
        }

        public void recover() {
            failed = false;
        }
    }

    /** 设备状态。 */
    public record DeviceStatus(String vendor, boolean connected,
                               boolean healthy, long lastReading,
                               long failures) {
    }

    private final Map<String, TimeDevice> devices =
            new ConcurrentHashMap<>();
    private final String primaryVendor;
    private final String backupVendor;
    private final long propagationDelayMillis;
    private volatile String activeVendor;
    private volatile CrossCloudTsoArbitration arbitration;
    private long lastTimestamp = Long.MIN_VALUE;
    private long switchovers;
    private long failures;

    public CommercialTimeDeviceConnector(String primaryVendor,
                                         String backupVendor,
                                         long propagationDelayMillis) {
        if (primaryVendor == null || primaryVendor.isBlank()
                || backupVendor == null
                || backupVendor.isBlank()
                || propagationDelayMillis < 0) {
            throw new IllegalArgumentException(
                    "vendors required and delay must be "
                            + "non-negative");
        }
        this.primaryVendor = primaryVendor;
        this.backupVendor = backupVendor;
        this.propagationDelayMillis = propagationDelayMillis;
    }

    public void registerDriver(TimeDevice device) {
        if (device == null || device.vendor() == null
                || device.vendor().isBlank()) {
            throw new IllegalArgumentException(
                    "device with vendor required");
        }
        devices.put(device.vendor(), device);
    }

    public void attachArbitration(
            CrossCloudTsoArbitration arbitration) {
        if (arbitration == null) {
            throw new IllegalArgumentException(
                    "arbitration required");
        }
        this.arbitration = arbitration;
    }

    public boolean connect(String vendor) {
        TimeDevice device = requireDevice(vendor);
        boolean ok = device.connect();
        if (ok) {
            activeVendor = vendor;
        }
        return ok;
    }

    /** 读取时间：主设备故障自动切换备设备，全部故障降级为上次值。 */
    public synchronized long timestamp() {
        TimeDevice device = pickDevice();
        if (device == null || !device.healthy()) {
            failures++;
            return lastTimestamp == Long.MIN_VALUE
                    ? 0 : lastTimestamp;
        }
        long corrected = device.readTimeMillis()
                + propagationDelayMillis;
        if (arbitration != null) {
            corrected = Math.max(corrected,
                    arbitration.timestamp());
        }
        long candidate = lastTimestamp == Long.MIN_VALUE
                ? corrected
                : Math.max(corrected, lastTimestamp + 1);
        lastTimestamp = candidate;
        return candidate;
    }

    public void disconnect(String vendor) {
        requireDevice(vendor).disconnect();
    }

    public DeviceStatus status(String vendor) {
        TimeDevice device = requireDevice(vendor);
        return new DeviceStatus(vendor, device.healthy(),
                device.healthy(), device.readTimeMillis(),
                failures);
    }

    public String activeVendor() {
        return activeVendor;
    }

    public long switchovers() {
        return switchovers;
    }

    public long failures() {
        return failures;
    }

    public boolean healthy() {
        return devices.values().stream()
                .anyMatch(TimeDevice::healthy);
    }

    private TimeDevice pickDevice() {
        TimeDevice primary = devices.get(primaryVendor);
        if (primary != null && primary.healthy()) {
            return primary;
        }
        TimeDevice backup = devices.get(backupVendor);
        if (backup != null && backup.connect()) {
            if (activeVendor != null
                    && !activeVendor.equals(backupVendor)) {
                switchovers++;
            }
            activeVendor = backupVendor;
            return backup;
        }
        return null;
    }

    private TimeDevice requireDevice(String vendor) {
        TimeDevice device = devices.get(vendor);
        if (device == null) {
            throw new IllegalArgumentException(
                    "unknown device vendor " + vendor);
        }
        return device;
    }
}
