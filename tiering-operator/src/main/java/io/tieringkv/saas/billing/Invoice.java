package io.tieringkv.saas.billing;

import io.tieringkv.saas.UsageMeter;

import java.util.List;

/** 账单（ADR-0130）：行项目 + 总价。 */
public record Invoice(String tenantId, String planId,
                      BillingPeriod period,
                      List<LineItem> lineItems) {

    public record LineItem(UsageMeter.MeterType type, long quantity,
                           double unitPrice, double subtotal) {
    }

    public Invoice {
        lineItems = List.copyOf(lineItems);
    }

    public double total() {
        return lineItems.stream().mapToDouble(
                LineItem::subtotal).sum();
    }
}
