package io.tieringkv.saas.commerce;

import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.ClusterTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** SaaS 市场目录（ADR-0146）：集群模板 + 计费计划注册与查询。 */
public final class MarketplaceCatalog {

    private final Map<String, ClusterTemplate> templates =
            new ConcurrentHashMap<>();
    private final Map<String, BillingPlan> plans =
            new ConcurrentHashMap<>();

    public void registerTemplate(ClusterTemplate template) {
        if (template == null) {
            throw new IllegalArgumentException("template required");
        }
        templates.put(template.templateId(), template);
    }

    public Optional<ClusterTemplate> template(String templateId) {
        return Optional.ofNullable(templates.get(templateId));
    }

    public void registerPlan(BillingPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan required");
        }
        plans.put(plan.planId(), plan);
    }

    public Optional<BillingPlan> plan(String planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    public List<String> templateIds() {
        return List.copyOf(templates.keySet());
    }

    public List<String> planIds() {
        return List.copyOf(plans.keySet());
    }

    public int templateCount() {
        return templates.size();
    }

    public int planCount() {
        return plans.size();
    }
}
