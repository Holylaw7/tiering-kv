package io.tieringkv.saas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SaaS 租户配额（ADR-0113）：region/存储校验。 */
class SaasQuotaTest {

    @Test
    void withinQuotaAllowed() {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 5, 100);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        assertThat(validator.validateAll(tenant, 3, 50)).isEmpty();
        validator.validate(tenant, 3, 50);
    }

    @Test
    void regionQuotaExceeded() {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 3, 100);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        assertThat(validator.validateAll(tenant, 4, 10))
                .anyMatch(violation -> violation.contains("regions"));
        assertThatThrownBy(() -> validator.validate(tenant, 4, 10))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void storageQuotaExceeded() {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 5, 100);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        assertThat(validator.validateAll(tenant, 2, 200))
                .anyMatch(violation -> violation.contains("storage"));
    }

    @Test
    void bothQuotasExceeded() {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 2, 10);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        List<String> violations = validator.validateAll(tenant, 5, 50);
        assertThat(violations).hasSize(2);
    }

    @Test
    void boundaryQuotaAllowed() {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 3, 100);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        assertThat(validator.validateAll(tenant, 3, 100)).isEmpty();
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedRegionQuota(int regions) {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", regions,
                100);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        validator.validate(tenant, regions, 50);
        assertThatThrownBy(() -> validator.validate(tenant,
                regions + 1, 50))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "storage {0}")
    @ValueSource(ints = {10, 100, 500})
    void parameterizedStorageQuota(int storage) {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 5,
                storage);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        validator.validate(tenant, 3, storage);
        assertThatThrownBy(() -> validator.validate(tenant, 3,
                storage + 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidQuotaRejected() {
        assertThatThrownBy(() -> new ClusterTenant("t1", "prod", 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClusterTenant("t1", "prod", 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
