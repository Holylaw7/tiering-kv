package io.tieringkv.security.network;

import java.util.ArrayList;
import java.util.List;

/** 网络策略 DSL（ADR-0169）：声明式 allow/deny 规则解析。 */
public final class NetworkPolicyDsl {

    private NetworkPolicyDsl() {
    }

    /** 策略规则：动作 + 租户对。 */
    public record PolicyRule(String action, String from,
                             String to) {
    }

    /**
     * 解析声明式策略：
     * allow: t1 -> t2
     * deny: t1 -> t3
     * 支持空行与 # 注释。
     */
    public static List<PolicyRule> parse(String dsl) {
        if (dsl == null) {
            throw new IllegalArgumentException("dsl required");
        }
        List<PolicyRule> rules = new ArrayList<>();
        for (String rawLine : dsl.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            rules.add(parseLine(line));
        }
        return rules;
    }

    private static PolicyRule parseLine(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException(
                    "malformed rule: " + line);
        }
        String action = line.substring(0, colon).trim();
        if (!action.equals("allow") && !action.equals("deny")) {
            throw new IllegalArgumentException(
                    "unknown action: " + action);
        }
        String rest = line.substring(colon + 1).trim();
        int arrow = rest.indexOf("->");
        if (arrow <= 0) {
            throw new IllegalArgumentException(
                    "missing arrow in rule: " + line);
        }
        String from = rest.substring(0, arrow).trim();
        String to = rest.substring(arrow + 2).trim();
        if (from.isEmpty() || to.isEmpty()) {
            throw new IllegalArgumentException(
                    "tenant required in rule: " + line);
        }
        return new PolicyRule(action, from, to);
    }
}
