package io.tieringkv.transaction.tso;

import io.tieringkv.transaction.tso.CommercialTimeDeviceConnector
        .DeviceStatus;
import io.tieringkv.transaction.tso.CommercialTimeDeviceConnector
        .SimulatedTimeDevice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 商用量子/卫星授时设备接入（ADR-0258）：SPI + 主备切换 + 单调。 */
class CommercialTimeDeviceConnectorTest {

    private CommercialTimeDeviceConnector connector() {
        return new CommercialTimeDeviceConnector("vendor-a",
                "vendor-b", 10);
    }

    private CommercialTimeDeviceConnector connectorWith(
            SimulatedTimeDevice primary,
            SimulatedTimeDevice backup) {
        CommercialTimeDeviceConnector connector = connector();
        connector.registerDriver(primary);
        connector.registerDriver(backup);
        connector.connect(primary.vendor());
        return connector;
    }

    @Test
    void connectAndReadWorks() {
        CommercialTimeDeviceConnector connector = connectorWith(
                new SimulatedTimeDevice("vendor-a", 1000, 0),
                new SimulatedTimeDevice("vendor-b", 2000, 0));
        assertThat(connector.activeVendor()).isEqualTo("vendor-a");
        assertThat(connector.timestamp()).isEqualTo(1010);
    }

    @Test
    void timestampIsMonotonic() {
        CommercialTimeDeviceConnector connector = connectorWith(
                new SimulatedTimeDevice("vendor-a", 1000, 0),
                new SimulatedTimeDevice("vendor-b", 2000, 0));
        long previous = connector.timestamp();
        for (int i = 0; i < 100; i++) {
            long next = connector.timestamp();
            assertThat(next).isGreaterThan(previous);
            previous = next;
        }
    }

    @Test
    void primaryFailureSwitchesToBackup() {
        SimulatedTimeDevice primary =
                new SimulatedTimeDevice("vendor-a", 1000, 0);
        CommercialTimeDeviceConnector connector = connectorWith(
                primary,
                new SimulatedTimeDevice("vendor-b", 2000, 0));
        primary.fail();
        long reading = connector.timestamp();
        assertThat(reading).isEqualTo(2010);
        assertThat(connector.activeVendor())
                .isEqualTo("vendor-b");
        assertThat(connector.switchovers()).isGreaterThan(0);
    }

    @Test
    void allDevicesFailedDegradesToLastReading() {
        SimulatedTimeDevice primary =
                new SimulatedTimeDevice("vendor-a", 1000, 0);
        SimulatedTimeDevice backup =
                new SimulatedTimeDevice("vendor-b", 2000, 0);
        CommercialTimeDeviceConnector connector = connectorWith(
                primary, backup);
        long first = connector.timestamp();
        primary.fail();
        backup.fail();
        assertThat(connector.timestamp()).isEqualTo(first);
        assertThat(connector.failures()).isGreaterThan(0);
    }

    @Test
    void backupRecoversAfterFailure() {
        SimulatedTimeDevice primary =
                new SimulatedTimeDevice("vendor-a", 1000, 0);
        CommercialTimeDeviceConnector connector = connectorWith(
                primary,
                new SimulatedTimeDevice("vendor-b", 2000, 0));
        primary.fail();
        connector.timestamp();
        primary.recover();
        assertThat(connector.connect("vendor-a")).isTrue();
        assertThat(connector.healthy()).isTrue();
    }

    @Test
    void arbitrationIntegrationKeepsMonotonic() {
        CommercialTimeDeviceConnector connector = connectorWith(
                new SimulatedTimeDevice("vendor-a", 1000, 0),
                new SimulatedTimeDevice("vendor-b", 2000, 0));
        CrossCloudTsoArbitration arbitration =
                new CrossCloudTsoArbitration(List.of(
                        new CrossCloudTsoArbitration
                                .CloudTimeSource("a", 5000)), 0,
                        100);
        connector.attachArbitration(arbitration);
        long reading = connector.timestamp();
        assertThat(reading).isGreaterThanOrEqualTo(5000);
    }

    @Test
    void statusReportReflectsDevice() {
        CommercialTimeDeviceConnector connector = connectorWith(
                new SimulatedTimeDevice("vendor-a", 1000, 0),
                new SimulatedTimeDevice("vendor-b", 2000, 0));
        DeviceStatus status = connector.status("vendor-a");
        assertThat(status.vendor()).isEqualTo("vendor-a");
        assertThat(status.healthy()).isTrue();
    }

    @Test
    void unknownVendorRejected() {
        CommercialTimeDeviceConnector connector = connector();
        assertThatThrownBy(() -> connector.connect("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "base={0} drift={1} delay={2} reads={3}")
    @MethodSource("monotonicMatrix")
    void monotonicAcrossDevices(long baseTime, long drift,
                                long delay, int reads) {
        CommercialTimeDeviceConnector connector =
                new CommercialTimeDeviceConnector("a", "b",
                        delay);
        connector.registerDriver(new SimulatedTimeDevice(
                "a", baseTime, drift));
        connector.registerDriver(new SimulatedTimeDevice(
                "b", baseTime + 500, drift));
        connector.connect("a");
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < reads; i++) {
            long reading = connector.timestamp();
            assertThat(reading).isGreaterThan(previous);
            previous = reading;
        }
    }

    @ParameterizedTest(name = "primary={0} backup={1}")
    @MethodSource("failoverMatrix")
    void failoverMatrixWorks(String primaryState,
                             String backupState) {
        CommercialTimeDeviceConnector connector =
                new CommercialTimeDeviceConnector("a", "b", 0);
        SimulatedTimeDevice primary = new SimulatedTimeDevice(
                "a", 1000, 0);
        SimulatedTimeDevice backup = new SimulatedTimeDevice(
                "b", 2000, 0);
        connector.registerDriver(primary);
        connector.registerDriver(backup);
        if ("failed".equals(primaryState)) {
            primary.fail();
        }
        if ("failed".equals(backupState)) {
            backup.fail();
        }
        if ("missing".equals(primaryState)) {
            connector = new CommercialTimeDeviceConnector("x", "b",
                    0);
            connector.registerDriver(backup);
        }
        long reading = connector.timestamp();
        boolean anyHealthy = connector.healthy();
        if (anyHealthy) {
            assertThat(reading).isGreaterThan(0);
        }
    }

    @ParameterizedTest(name = "sources={0} skew={1}")
    @MethodSource("arbitrationMatrix")
    void arbitrationMatrixMonotonic(int sources, long maxSkew) {
        CrossCloudTsoArbitration arbitration =
                new CrossCloudTsoArbitration(
                        java.util.stream.IntStream.range(0,
                                        sources)
                                .mapToObj(i -> new CrossCloudTsoArbitration
                                        .CloudTimeSource("s" + i,
                                        1000 + i * 10L))
                                .toList(), maxSkew, 100);
        CommercialTimeDeviceConnector connector =
                new CommercialTimeDeviceConnector("a", "b", 5);
        connector.registerDriver(new SimulatedTimeDevice(
                "a", 1000, 0));
        connector.registerDriver(new SimulatedTimeDevice(
                "b", 1500, 0));
        connector.connect("a");
        connector.attachArbitration(arbitration);
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < 10; i++) {
            long reading = connector.timestamp();
            assertThat(reading).isGreaterThan(previous);
            previous = reading;
        }
    }

    @ParameterizedTest(name = "invalid {0}")
    @MethodSource("validationMatrix")
    void invalidInputsRejected(String caseName) {
        assertThatThrownBy(() -> {
            switch (caseName) {
                case "null-primary" -> new CommercialTimeDeviceConnector(
                        null, "b", 0);
                case "blank-primary" -> new CommercialTimeDeviceConnector(
                        " ", "b", 0);
                case "null-backup" -> new CommercialTimeDeviceConnector(
                        "a", null, 0);
                case "blank-backup" -> new CommercialTimeDeviceConnector(
                        "a", " ", 0);
                case "negative-delay" -> new CommercialTimeDeviceConnector(
                        "a", "b", -1);
                case "null-device" -> {
                    CommercialTimeDeviceConnector connector =
                            connector();
                    connector.registerDriver(null);
                }
                case "blank-vendor" -> {
                    CommercialTimeDeviceConnector connector =
                            connector();
                    connector.registerDriver(new SimulatedTimeDevice(
                            " ", 0, 0));
                }
                case "connect-unknown" -> connector()
                        .connect("nope");
                case "null-arbitration" -> connector()
                        .attachArbitration(null);
                case "disconnect-unknown" -> connector()
                        .disconnect("nope");
                default -> throw new IllegalArgumentException(
                        "unknown case");
            }
        }).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> monotonicMatrix() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (long base : new long[]{100, 1_000_000}) {
            for (long drift : new long[]{0, 1, 1000}) {
                for (long delay : new long[]{0, 5, 50}) {
                    for (int reads : new int[]{5, 10}) {
                        builder.add(Arguments.of(base, drift,
                                delay, reads));
                    }
                }
            }
        }
        return builder.build();
    }

    static Stream<Arguments> failoverMatrix() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (String primary : new String[]{"healthy", "failed",
                "missing"}) {
            for (String backup : new String[]{"healthy",
                    "failed"}) {
                builder.add(Arguments.of(primary, backup));
            }
        }
        return builder.build();
    }

    static Stream<Arguments> arbitrationMatrix() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (int sources = 1; sources <= 5; sources++) {
            for (long skew : new long[]{0, 10, 100}) {
                builder.add(Arguments.of(sources, skew));
            }
        }
        return builder.build();
    }

    static Stream<Arguments> validationMatrix() {
        return Stream.of("null-primary", "blank-primary",
                        "null-backup", "blank-backup",
                        "negative-delay", "null-device",
                        "blank-vendor", "connect-unknown",
                        "null-arbitration", "disconnect-unknown")
                .map(Arguments::of);
    }
}
