package io.tieringkv.security.network;

/** 策略风险评分（ADR-0184）：规则驱动 0~100。 */
public final class PolicyRiskScorer {

    /** 风险评分结果。 */
    public record RiskScore(int score, int allowPairs,
                            boolean privateExposure) {
    }

    private static final int PAIR_PENALTY = 10;
    private static final int PRIVATE_PENALTY = 20;
    private static final int MAX_SCORE = 100;

    public RiskScore score(IsolationPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException(
                    "policy required");
        }
        int allowPairs = policy.whitelistEntries().size();
        boolean privateExposure = policy.whitelistEntries().stream()
                .anyMatch(pair -> exposesPrivate(policy, pair));
        int score = Math.min(MAX_SCORE,
                allowPairs * PAIR_PENALTY
                        + (privateExposure ? PRIVATE_PENALTY : 0));
        return new RiskScore(score, allowPairs, privateExposure);
    }

    private static boolean exposesPrivate(IsolationPolicy policy,
                                          String pair) {
        String[] tenants = pair.split(":", 2);
        return policy.isPrivate(tenants[0])
                || policy.isPrivate(tenants[1]);
    }
}
