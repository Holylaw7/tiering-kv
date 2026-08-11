package io.tieringkv.compliance;

import java.util.Map;

/** 数据驻留策略（ADR-0143）：地域 → 驻留要求。 */
public record DataResidencyPolicy(Map<String, String> residency) {

    public DataResidencyPolicy {
        residency = Map.copyOf(residency);
    }

    public String required(String region) {
        return residency.getOrDefault(region, "default");
    }
}
