package io.tieringkv.security.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 网络隔离策略（ADR-0161）：默认拒绝 + 白名单。 */
class IsolationPolicyTest {

    @Test
    void sameTenantAllowed() {
        IsolationPolicy policy = policy();
        assertThat(policy.canCommunicate("t1", "t1")).isTrue();
    }

    @Test
    void crossTenantDefaultDenied() {
        IsolationPolicy policy = policy();
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
        assertThat(policy.canCommunicate("t2", "t1")).isFalse();
    }

    @Test
    void whitelistAllowsBothDirections() {
        IsolationPolicy policy = policy();
        policy.allow("t1", "t2");
        assertThat(policy.canCommunicate("t1", "t2")).isTrue();
        assertThat(policy.canCommunicate("t2", "t1")).isTrue();
    }

    @Test
    void denyRevokesAuthorization() {
        IsolationPolicy policy = policy();
        policy.allow("t1", "t2");
        policy.deny("t1", "t2");
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    @Test
    void unknownTenantDenied() {
        IsolationPolicy policy = policy();
        assertThat(policy.canCommunicate("missing", "t1")).isFalse();
        assertThat(policy.canCommunicate("t1", "missing")).isFalse();
    }

    @Test
    void domainLookup() {
        IsolationPolicy policy = policy();
        NetworkIsolationDomain domain = policy.domain("t1");
        assertThat(domain.vpcId()).isEqualTo("vpc-1");
    }

    @Test
    void unknownDomainLookupRejected() {
        assertThatThrownBy(() -> policy().domain("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void privateFlagPerTenant() {
        IsolationPolicy policy = policy();
        assertThat(policy.isPrivate("t1")).isTrue();
        assertThat(policy.isPrivate("t3")).isFalse();
    }

    @Test
    void whitelistEntriesListed() {
        IsolationPolicy policy = policy();
        policy.allow("t1", "t2");
        assertThat(policy.whitelistEntries()).containsExactly("t1:t2");
    }

    @Test
    void whitelistPairNormalized() {
        IsolationPolicy policy = policy();
        policy.allow("t2", "t1");
        assertThat(policy.whitelistEntries()).containsExactly("t1:t2");
        assertThat(policy.canCommunicate("t1", "t2")).isTrue();
    }

    @Test
    void clearWhitelistResets() {
        IsolationPolicy policy = policy();
        policy.allow("t1", "t2");
        policy.clearWhitelist();
        assertThat(policy.whitelistEntries()).isEmpty();
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    @Test
    void nullDomainRejected() {
        assertThatThrownBy(() -> policy().register(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowUnknownTenantRejected() {
        IsolationPolicy policy = policy();
        assertThatThrownBy(() -> policy.allow("missing", "t2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.allow("t1", "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerOverwritesDomain() {
        IsolationPolicy policy = policy();
        policy.register(new NetworkIsolationDomain("t1",
                "vpc-9", "subnet-9", false));
        assertThat(policy.domain("t1").vpcId()).isEqualTo("vpc-9");
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {2, 5, 20})
    void parameterizedTenantCounts(int count) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i, true));
        }
        assertThat(policy.domainCount()).isEqualTo(count);
        for (int i = 0; i < count; i++) {
            assertThat(policy.canCommunicate("t" + i, "t" + i))
                    .isTrue();
            assertThat(policy.canCommunicate("t" + i,
                    "t" + ((i + 1) % count))).isFalse();
        }
    }

    @ParameterizedTest(name = "pairs {0}")
    @ValueSource(ints = {1, 10, 50})
    void parameterizedWhitelistPairs(int pairs) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i <= pairs; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        for (int i = 0; i < pairs; i++) {
            assertThat(policy.canCommunicate("t" + i,
                    "t" + (i + 1))).isTrue();
        }
        assertThat(policy.whitelistEntries()).hasSize(pairs);
    }

    @Test
    void concurrentRegistrationAndCheck() throws Exception {
        IsolationPolicy policy = new IsolationPolicy();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                policy.register(new NetworkIsolationDomain(
                        "t" + i, "vpc", "subnet", true));
                if (i > 0) {
                    policy.allow("t" + (i - 1), "t" + i);
                }
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                policy.canCommunicate("t" + (i % 100),
                        "t" + ((i + 1) % 100));
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(policy.domainCount()).isEqualTo(100);
    }

    @Test
    void denyBetweenUnauthorizedPairNoop() {
        IsolationPolicy policy = policy();
        policy.deny("t1", "t2");
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    @Test
    void privateTenantsStillNeedWhitelist() {
        IsolationPolicy policy = policy();
        assertThat(policy.isPrivate("t1")).isTrue();
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    @Test
    void vpcSharedDoesNotImplyAccess() {
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc-shared", "subnet-1", true));
        policy.register(new NetworkIsolationDomain(
                "t2", "vpc-shared", "subnet-2", true));
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    private static IsolationPolicy policy() {
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc-1", "subnet-1", true));
        policy.register(new NetworkIsolationDomain(
                "t2", "vpc-2", "subnet-2", true));
        policy.register(new NetworkIsolationDomain(
                "t3", "vpc-3", "subnet-3", false));
        return policy;
    }
}
