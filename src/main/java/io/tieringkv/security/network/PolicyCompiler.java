package io.tieringkv.security.network;

import io.tieringkv.security.network.NetworkPolicyDsl.PolicyRule;

import java.util.List;

/** 策略编译（ADR-0169）：DSL → IsolationPolicy，幂等。 */
public final class PolicyCompiler {

    /** 编译并应用到隔离策略：规则顺序执行。 */
    public void apply(IsolationPolicy policy, String dsl) {
        if (policy == null) {
            throw new IllegalArgumentException(
                    "policy required");
        }
        List<PolicyRule> rules = NetworkPolicyDsl.parse(dsl);
        for (PolicyRule rule : rules) {
            if (rule.action().equals("allow")) {
                policy.allow(rule.from(), rule.to());
            } else {
                policy.deny(rule.from(), rule.to());
            }
        }
    }

    /** 幂等应用：重复编译不改变最终白名单状态。 */
    public void applyIdempotent(IsolationPolicy policy,
                                String dsl) {
        apply(policy, dsl);
    }
}
