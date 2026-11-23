package io.tieringkv.saas.billing;

/** 计费周期（ADR-0130）：起止 + 冻结。 */
public record BillingPeriod(long startMillis, long endMillis,
                            boolean frozen) {
}
