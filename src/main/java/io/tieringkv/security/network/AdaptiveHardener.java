package io.tieringkv.security.network;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 自适应加固（ADR-0190）：评分阈值 → 撤销高风险白名单 + 回滚。 */
public final class AdaptiveHardener {

    private final PolicyRiskScorer scorer = new PolicyRiskScorer();
    private final List<String> revokedPairs =
            new CopyOnWriteArrayList<>();

    /** 加固：评分达到阈值则撤销全部白名单，返回撤销数量。 */
    public int harden(IsolationPolicy policy, int riskThreshold,
                      NetworkPolicyAudit audit) {
        if (policy == null || audit == null) {
            throw new IllegalArgumentException(
                    "policy and audit required");
        }
        int score = scorer.score(policy).score();
        if (score < riskThreshold) {
            return 0;
        }
        List<String> pairs = new ArrayList<>(
                policy.whitelistEntries());
        for (String pair : pairs) {
            String[] tenants = pair.split(":", 2);
            policy.deny(tenants[0], tenants[1]);
            revokedPairs.add(pair);
            audit.record("hardening:revoke " + pair,
                    new NetworkPolicyDsl.PolicyRule("deny",
                            tenants[0], tenants[1]),
                    System.currentTimeMillis());
        }
        return pairs.size();
    }

    /** 回滚：重新允许被撤销的全部白名单。 */
    public int rollback(IsolationPolicy policy,
                        NetworkPolicyAudit audit) {
        if (policy == null || audit == null) {
            throw new IllegalArgumentException(
                    "policy and audit required");
        }
        int restored = 0;
        for (String pair : revokedPairs) {
            String[] tenants = pair.split(":", 2);
            policy.allow(tenants[0], tenants[1]);
            audit.record("hardening:restore " + pair,
                    new NetworkPolicyDsl.PolicyRule("allow",
                            tenants[0], tenants[1]),
                    System.currentTimeMillis());
            restored++;
        }
        revokedPairs.clear();
        return restored;
    }

    public int revokedCount() {
        return revokedPairs.size();
    }
}
