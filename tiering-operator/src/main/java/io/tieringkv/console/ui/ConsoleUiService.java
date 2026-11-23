package io.tieringkv.console.ui;

import io.tieringkv.console.ConsoleApi;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.saas.billing.BillingScheduler;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Permission;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 控制台 UI 原型（ADR-0146）：静态 HTML 视图（总览/租户/账单/指标/告警）
 * + REST 调用 + RBAC 门控（ADMIN/READ）。
 */
public final class ConsoleUiService {

    /** 渲染结果：HTTP 状态 + 标题 + HTML。 */
    public record Page(int status, String title, String html) {
    }

    private final ConsoleApi api;
    private final BillingScheduler billing;
    private final CredentialManager credentials;

    public ConsoleUiService(ConsoleApi api, BillingScheduler billing,
                            CredentialManager credentials) {
        this.api = api;
        this.billing = billing;
        this.credentials = credentials;
    }

    public Page render(String token, String view) {
        return switch (view) {
            case "overview" -> overview(token);
            case "tenants" -> tenants(token);
            case "billing" -> billingView(token);
            case "metrics" -> metrics(token);
            case "alerts" -> alerts(token);
            default -> new Page(404, "Not Found",
                    "<h1>404 Not Found</h1>");
        };
    }

    private Page overview(String token) {
        try {
            List<String> tenants;
            List<String> alertList;
            try {
                tenants = api.listTenants(token);
            } catch (SecurityException e) {
                tenants = List.of();
            }
            try {
                alertList = api.alerts(token);
            } catch (SecurityException e) {
                alertList = List.of();
            }
            Map<String, Long> snapshot = api.metrics(token);
            String body = "<h1>Overview</h1>"
                    + "<p>Tenants: " + tenants.size() + "</p>"
                    + "<p>Alerts: " + alertList.size() + "</p>"
                    + metricTable(snapshot);
            return page("Overview", body);
        } catch (SecurityException e) {
            return forbidden();
        }
    }

    private Page tenants(String token) {
        try {
            List<String> tenantIds = api.listTenants(token);
            StringBuilder rows = new StringBuilder();
            for (String tenantId : tenantIds) {
                rows.append("<tr><td>")
                        .append(escape(tenantId))
                        .append("</td></tr>");
            }
            String body = "<h1>Tenants</h1><table>"
                    + "<tr><th>Tenant ID</th></tr>"
                    + rows + "</table>"
                    + "<form action=\"/tenants\" method=\"post\">"
                    + "<input name=\"tenantId\" placeholder=\"tenant id\"/>"
                    + "<button>Create</button></form>";
            return page("Tenants", body);
        } catch (SecurityException e) {
            return forbidden();
        }
    }

    private Page billingView(String token) {
        try {
            credentials.require(token, Permission.ADMIN);
            List<String> tenantIds = api.listTenants(token);
            StringBuilder rows = new StringBuilder();
            for (String tenantId : tenantIds) {
                UsageMeter meter = billing.meter(tenantId);
                rows.append("<tr><td>").append(escape(tenantId))
                        .append("</td><td>")
                        .append(meter.get(UsageMeter.MeterType.REQUESTS))
                        .append("</td><td>")
                        .append(meter.get(UsageMeter.MeterType.STORAGE_GB))
                        .append("</td><td>")
                        .append(meter.get(UsageMeter.MeterType.EGRESS_GB))
                        .append("</td></tr>");
            }
            String body = "<h1>Billing</h1><table>"
                    + "<tr><th>Tenant</th><th>Requests</th>"
                    + "<th>Storage GB</th><th>Egress GB</th></tr>"
                    + rows + "</table>";
            return page("Billing", body);
        } catch (SecurityException e) {
            return forbidden();
        }
    }

    private Page metrics(String token) {
        try {
            return page("Metrics", "<h1>Metrics</h1>"
                    + metricTable(api.metrics(token)));
        } catch (SecurityException e) {
            return forbidden();
        }
    }

    private Page alerts(String token) {
        try {
            List<String> alertList = api.alerts(token);
            StringBuilder rows = new StringBuilder();
            for (String alert : alertList) {
                rows.append("<li>").append(escape(alert))
                        .append("</li>");
            }
            String body = "<h1>Alerts</h1><ul>" + rows + "</ul>";
            return page("Alerts", body);
        } catch (SecurityException e) {
            return forbidden();
        }
    }

    private static String metricTable(Map<String, Long> snapshot) {
        String rows = snapshot.entrySet().stream()
                .map(entry -> "<tr><td>" + escape(entry.getKey())
                        + "</td><td>" + entry.getValue() + "</td></tr>")
                .collect(Collectors.joining());
        return "<table><tr><th>Metric</th><th>Value</th></tr>"
                + rows + "</table>";
    }

    private static Page page(String title, String body) {
        String html = "<!DOCTYPE html><html><head><title>"
                + escape(title) + "</title></head><body>"
                + body + "</body></html>";
        return new Page(200, title, html);
    }

    private static Page forbidden() {
        return new Page(403, "Forbidden", "<h1>403 Forbidden</h1>");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
