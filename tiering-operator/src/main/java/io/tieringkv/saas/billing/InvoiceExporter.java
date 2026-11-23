package io.tieringkv.saas.billing;

import java.util.stream.Collectors;

/** 账单导出（ADR-0130）：CSV / JSON。 */
public final class InvoiceExporter {

    public String toCsv(Invoice invoice) {
        StringBuilder builder = new StringBuilder();
        builder.append("tenant,plan,type,quantity,unitPrice,subtotal\n");
        for (Invoice.LineItem item : invoice.lineItems()) {
            builder.append(invoice.tenantId()).append(',')
                    .append(invoice.planId()).append(',')
                    .append(item.type()).append(',')
                    .append(item.quantity()).append(',')
                    .append(item.unitPrice()).append(',')
                    .append(item.subtotal()).append('\n');
        }
        return builder.toString();
    }

    public String toJson(Invoice invoice) {
        String items = invoice.lineItems().stream()
                .map(item -> "{\"type\":\"" + item.type()
                        + "\",\"quantity\":" + item.quantity()
                        + ",\"unitPrice\":" + item.unitPrice()
                        + ",\"subtotal\":" + item.subtotal() + "}")
                .collect(Collectors.joining(","));
        return "{\"tenant\":\"" + invoice.tenantId()
                + "\",\"plan\":\"" + invoice.planId()
                + "\",\"total\":" + invoice.total()
                + ",\"lineItems\":[" + items + "]}";
    }
}
