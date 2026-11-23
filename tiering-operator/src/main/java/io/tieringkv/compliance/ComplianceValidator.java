package io.tieringkv.compliance;

/** 合规校验（ADR-0143）：跨驻留边界默认拒绝。 */
public final class ComplianceValidator {

    public void validate(DataResidencyPolicy policy,
                         String fromRegion, String toRegion) {
        String from = policy.required(fromRegion);
        String to = policy.required(toRegion);
        if (!from.equals(to)) {
            throw new SecurityException(
                    "data residency violation: " + fromRegion
                            + "(" + from + ") -> " + toRegion
                            + "(" + to + ")");
        }
    }
}
