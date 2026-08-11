package io.tieringkv.saas;

import java.util.Map;

/** 计费计划（ADR-0124）：计量维度 → 单价。 */
public record BillingPlan(String planId,
                          Map<UsageMeter.MeterType, Double> prices) {

    public BillingPlan {
        prices = Map.copyOf(prices);
    }
}
