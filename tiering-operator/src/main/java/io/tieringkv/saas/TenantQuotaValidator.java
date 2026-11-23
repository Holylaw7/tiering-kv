package io.tieringkv.saas;

import java.util.ArrayList;
import java.util.List;

/** 租户配额校验（ADR-0113）：region/存储超限拒绝。 */
public final class TenantQuotaValidator {

    public void validate(ClusterTenant tenant, int regionCount,
                         int storageGB) {
        List<String> violations = validateAll(tenant, regionCount,
                storageGB);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "quota violated: " + violations);
        }
    }

    public List<String> validateAll(ClusterTenant tenant, int regionCount,
                                    int storageGB) {
        List<String> violations = new ArrayList<>();
        if (regionCount > tenant.maxRegions()) {
            violations.add("regions " + regionCount + " > "
                    + tenant.maxRegions());
        }
        if (storageGB > tenant.maxStorageGB()) {
            violations.add("storage " + storageGB + "GB > "
                    + tenant.maxStorageGB() + "GB");
        }
        return violations;
    }
}
