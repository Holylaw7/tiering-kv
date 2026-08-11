package io.tieringkv.saas;

/** 计量计费（ADR-0124）：usage × price。 */
public final class MeteredBilling {

    public double calculate(UsageMeter meter, BillingPlan plan) {
        double total = 0;
        for (UsageMeter.MeterType type : UsageMeter.MeterType.values()) {
            Double price = plan.prices().get(type);
            if (price != null) {
                total += meter.get(type) * price;
            }
        }
        return total;
    }
}
