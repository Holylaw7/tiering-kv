package io.tieringkv.gateway;

import io.tieringkv.gateway.PriorityRouter.Decision;
import io.tieringkv.gateway.PriorityRouter.Priority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 全球多活优先级路由（ADR-0149）：配额 + 降级 + 丢弃。 */
class PriorityRouterTest {

    @Test
    void normalRouteAcceptedWithinQuota() {
        Fixture fixture = fixture();
        Decision decision = fixture.router().route(
                bytes("user:1"), Priority.NORMAL);
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.degraded()).isFalse();
    }

    @Test
    void lowPriorityDroppedWhenQuotaFull() {
        Fixture fixture = fixture();
        for (int i = 0; i < 5; i++) {
            assertThat(fixture.router().route(bytes("user:9"),
                    Priority.LOW).accepted())
                    .isTrue();
        }
        Decision decision = fixture.router().route(
                bytes("user:9"), Priority.LOW);
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.degraded()).isFalse();
    }

    @Test
    void normalDegradesToFallbackRegion() {
        Fixture fixture = fixture();
        for (int i = 0; i < 5; i++) {
            fixture.router().route(bytes("user:9"),
                    Priority.NORMAL);
        }
        Decision decision = fixture.router().route(
                bytes("user:9"), Priority.NORMAL);
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.degraded()).isTrue();
    }

    @Test
    void degradationDisabledRejects() {
        Fixture fixture = fixture(false);
        for (int i = 0; i < 5; i++) {
            fixture.router().route(bytes("user:9"),
                    Priority.NORMAL);
        }
        Decision decision = fixture.router().route(
                bytes("user:9"), Priority.NORMAL);
        assertThat(decision.accepted()).isFalse();
    }

    @Test
    void highPriorityDegradesWhenQuotaFull() {
        Fixture fixture = fixture();
        for (int i = 0; i < 5; i++) {
            fixture.router().route(bytes("user:9"),
                    Priority.HIGH);
        }
        Decision decision = fixture.router().route(
                bytes("user:9"), Priority.HIGH);
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.degraded()).isTrue();
    }

    @Test
    void degradedDecisionCarriesFallbackRegion() {
        Fixture fixture = fixture();
        for (int i = 0; i < 5; i++) {
            fixture.router().route(bytes("user:9"),
                    Priority.NORMAL);
        }
        Decision decision = fixture.router().route(
                bytes("user:9"), Priority.NORMAL);
        assertThat(decision.region()).isNotEqualTo(
                fixture.affinity().route(bytes("user:9")));
    }

    @Test
    void allRegionsFullRejects() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 1);
        quota.setQuota("r2", 1);
        PriorityRouter router = new PriorityRouter(
                new RegionAffinityRouter(List.of("r1", "r2")),
                quota, List.of("r1", "r2"), true);
        quota.tryAcquire("r1");
        quota.tryAcquire("r2");
        assertThat(router.route(bytes("k3"), Priority.HIGH)
                .accepted()).isFalse();
    }

    @Test
    void quotaResetRestoresRouting() {
        Fixture fixture = fixture();
        for (int i = 0; i < 5; i++) {
            fixture.router().route(bytes("user:9"),
                    Priority.NORMAL);
        }
        fixture.quota().resetCycle();
        assertThat(fixture.router().route(bytes("user:9"),
                Priority.NORMAL).accepted()).isTrue();
    }

    @ParameterizedTest(name = "priority {0}")
    @CsvSource({"LOW,false", "NORMAL,true", "HIGH,true"})
    void parameterizedPriorityDegrade(String priority, boolean accepted) {
        Fixture fixture = fixture();
        for (int i = 0; i < 5; i++) {
            fixture.router().route(bytes("user:9"),
                    Priority.NORMAL);
        }
        Decision decision = fixture.router().route(
                bytes("user:9"),
                Priority.valueOf(priority));
        assertThat(decision.accepted()).isEqualTo(accepted);
        assertThat(decision.degraded()).isEqualTo(accepted);
    }

    @ParameterizedTest(name = "keys {0}")
    @CsvSource({"1", "10", "100"})
    void parameterizedKeyCounts(int count) {
        Fixture fixture = fixture();
        int accepted = 0;
        for (int i = 0; i < count; i++) {
            if (fixture.router().route(bytes("user:" + i),
                    Priority.NORMAL).accepted()) {
                accepted++;
            }
        }
        assertThat(accepted).isEqualTo(Math.min(count, 10));
    }

    @Test
    void affinityRoutesWithinQuota() {
        Fixture fixture = fixture();
        String region = fixture.affinity().route(bytes("user:1"));
        Decision decision = fixture.router().route(
                bytes("user:1"), Priority.NORMAL);
        assertThat(decision.region()).isEqualTo(region);
    }

    private static Fixture fixture() {
        return fixture(true);
    }

    private static Fixture fixture(boolean degradeEnabled) {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 5);
        quota.setQuota("r2", 5);
        RegionAffinityRouter affinity =
                new RegionAffinityRouter(List.of("r1", "r2"));
        PriorityRouter router = new PriorityRouter(affinity, quota,
                List.of("r1", "r2"), degradeEnabled);
        return new Fixture(quota, affinity, router);
    }

    private record Fixture(RegionQuota quota,
                           RegionAffinityRouter affinity,
                           PriorityRouter router) {
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
