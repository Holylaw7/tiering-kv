package io.tieringkv.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全球多活网关冲突审计（ADR-0141）：亲和路由 + 审计。 */
class GatewayConflictAuditTest {

    @Test
    void affinityRoutesByHash() {
        RegionAffinityRouter router = new RegionAffinityRouter(
                List.of("r1", "r2"));
        String region = router.route(bytes("user:1"));
        assertThat(region).isIn("r1", "r2");
        assertThat(router.route(bytes("user:1")))
                .isEqualTo(region);
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedAffinityRegions(int count) {
        List<String> regions = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            regions.add("r" + i);
        }
        RegionAffinityRouter router = new RegionAffinityRouter(regions);
        assertThat(router.route(bytes("k"))).isIn(regions);
    }

    @Test
    void emptyRegionsRejected() {
        assertThatThrownBy(() -> new RegionAffinityRouter(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditLogRecordsEntries() {
        ConflictAuditLog audit = new ConflictAuditLog();
        audit.audit("r1", "k1", "r2");
        audit.audit("r2", "k1", "r2");
        assertThat(audit.size()).isEqualTo(2);
        assertThat(audit.byKey("k1")).hasSize(2);
        assertThat(audit.entries().get(0).winner()).isEqualTo("r2");
    }

    @Test
    void auditLogEmpty() {
        ConflictAuditLog audit = new ConflictAuditLog();
        assertThat(audit.size()).isZero();
        assertThat(audit.byKey("k")).isEmpty();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 100, 1000})
    void parameterizedAuditVolume(int count) {
        ConflictAuditLog audit = new ConflictAuditLog();
        for (int i = 0; i < count; i++) {
            audit.audit("r1", "k" + i, "r2");
        }
        assertThat(audit.size()).isEqualTo(count);
    }

    @Test
    void affinityStableAcrossKeys() {
        RegionAffinityRouter router = new RegionAffinityRouter(
                List.of("r1", "r2", "r3"));
        for (int i = 0; i < 100; i++) {
            byte[] key = bytes("k" + i);
            assertThat(router.route(key)).isEqualTo(router.route(key));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
