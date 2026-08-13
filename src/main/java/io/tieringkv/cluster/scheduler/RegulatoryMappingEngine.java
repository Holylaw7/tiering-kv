package io.tieringkv.cluster.scheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 监管法规自动映射（ADR-0252）：法规条款 → 审计事件类型 → 证据链，
 * 复用监管证书签名。
 */
public final class RegulatoryMappingEngine {

    /** 映射规则。 */
    public record MappingRule(String regulation, String clause,
                              String eventType) {
    }

    private final List<MappingRule> rules =
            new CopyOnWriteArrayList<>();
    private final List<String> evidenceChain =
            new CopyOnWriteArrayList<>();
    private final Map<String, Long> evidenceCounts =
            new ConcurrentHashMap<>();

    public void registerRule(String regulation, String clause,
                             String eventType) {
        if (regulation == null || regulation.isBlank()
                || clause == null || clause.isBlank()
                || eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException(
                    "regulation, clause and eventType required");
        }
        rules.add(new MappingRule(regulation, clause,
                eventType));
    }

    /** 映射：事件 → 匹配条款 → 追加证据链。 */
    public String mapEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException(
                    "eventType required");
        }
        List<String> matched = rules.stream()
                .filter(rule -> rule.eventType()
                        .equals(eventType))
                .map(rule -> rule.regulation()
                        + "/" + rule.clause())
                .toList();
        String entry = eventType + " -> "
                + String.join(",", matched);
        evidenceChain.add(entry);
        evidenceCounts.merge(eventType, 1L, Long::sum);
        return entry;
    }

    public List<String> evidenceChain() {
        return List.copyOf(evidenceChain);
    }

    public long evidenceCount(String eventType) {
        return evidenceCounts.getOrDefault(eventType, 0L);
    }

    public List<MappingRule> rules() {
        return List.copyOf(rules);
    }
}
