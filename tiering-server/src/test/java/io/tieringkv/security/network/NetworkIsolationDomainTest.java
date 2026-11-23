package io.tieringkv.security.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 租户网络域（ADR-0161）：字段校验 + 私有标志。 */
class NetworkIsolationDomainTest {

    @Test
    void domainCarriesFields() {
        NetworkIsolationDomain domain = new NetworkIsolationDomain(
                "t1", "vpc-1", "subnet-1", true);
        assertThat(domain.tenantId()).isEqualTo("t1");
        assertThat(domain.vpcId()).isEqualTo("vpc-1");
        assertThat(domain.subnetId()).isEqualTo("subnet-1");
        assertThat(domain.privateNetwork()).isTrue();
    }

    @Test
    void publicNetworkFlag() {
        assertThat(new NetworkIsolationDomain("t1", "vpc-1",
                "subnet-1", false).privateNetwork()).isFalse();
    }

    @Test
    void blankTenantRejected() {
        assertThatThrownBy(() -> new NetworkIsolationDomain(
                "", "vpc-1", "subnet-1", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankVpcRejected() {
        assertThatThrownBy(() -> new NetworkIsolationDomain(
                "t1", " ", "subnet-1", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankSubnetRejected() {
        assertThatThrownBy(() -> new NetworkIsolationDomain(
                "t1", "vpc-1", "", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameVpcDifferentSubnetsDistinct() {
        NetworkIsolationDomain first = new NetworkIsolationDomain(
                "t1", "vpc-1", "subnet-1", true);
        NetworkIsolationDomain second = new NetworkIsolationDomain(
                "t2", "vpc-1", "subnet-2", true);
        assertThat(first).isNotEqualTo(second);
    }

    @ParameterizedTest(name = "tenant {0}")
    @ValueSource(strings = {"t1", "tenant-42", "prod"})
    void parameterizedTenants(String tenantId) {
        NetworkIsolationDomain domain = new NetworkIsolationDomain(
                tenantId, "vpc-1", "subnet-1", true);
        assertThat(domain.tenantId()).isEqualTo(tenantId);
    }

    @ParameterizedTest(name = "private {0}")
    @ValueSource(booleans = {true, false})
    void parameterizedPrivacy(boolean privacy) {
        NetworkIsolationDomain domain = new NetworkIsolationDomain(
                "t1", "vpc-1", "subnet-1", privacy);
        assertThat(domain.privateNetwork()).isEqualTo(privacy);
    }
}
