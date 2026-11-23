package io.tieringkv.datamesh;

import io.tieringkv.datamesh.ObjectStorageArchive.ArchivedObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 对象生命周期（ADR-0203）：TTL → 过期规则 + 恢复保护。 */
public final class ObjectLifecycleManager {

    /** 生命周期规则：对象 key 前缀 + 过期天数。 */
    public record LifecycleRule(String keyPrefix,
                                long expirationDays) {

        public LifecycleRule {
            if (keyPrefix == null || keyPrefix.isBlank()
                    || expirationDays < 0) {
                throw new IllegalArgumentException(
                        "invalid rule");
            }
        }
    }

    /** 规则生成审计记录。 */
    public record RuleApplied(String objectKey, LifecycleRule rule,
                              long timestampMillis) {
    }

    private final List<LifecycleRule> rules =
            new CopyOnWriteArrayList<>();
    private final List<RuleApplied> applied =
            new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> protection =
            new ConcurrentHashMap<>();

    public void addRule(LifecycleRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule required");
        }
        rules.add(rule);
    }

    /** 应用规则到对象：TTL 匹配 → 生成过期策略（不删除）。 */
    public boolean apply(ArchivedObject object,
                         long currentMillis) {
        if (object == null) {
            throw new IllegalArgumentException(
                    "object required");
        }
        LifecycleRule matching = rules.stream()
                .filter(rule -> object.objectKey()
                        .startsWith(rule.keyPrefix()))
                .findFirst().orElse(null);
        if (matching == null) {
            return false;
        }
        applied.add(new RuleApplied(object.objectKey(), matching,
                currentMillis));
        return true;
    }

    /** 过期判定：对象年龄 > 规则过期天数。 */
    public boolean expired(ArchivedObject object,
                           long currentMillis) {
        if (object == null) {
            throw new IllegalArgumentException(
                    "object required");
        }
        LifecycleRule matching = rules.stream()
                .filter(rule -> object.objectKey()
                        .startsWith(rule.keyPrefix()))
                .findFirst().orElse(null);
        if (matching == null) {
            return false;
        }
        long ageDays = (currentMillis - object.archivedAtMillis())
                / (24 * 60 * 60 * 1000);
        return ageDays > matching.expirationDays();
    }

    public void protect(String objectKey) {
        protection.put(objectKey, true);
    }

    public boolean isProtected(String objectKey) {
        return protection.getOrDefault(objectKey, false);
    }

    public List<LifecycleRule> rules() {
        return List.copyOf(rules);
    }

    public List<RuleApplied> applied() {
        return List.copyOf(applied);
    }
}
