package io.tieringkv.console.ui;

import io.tieringkv.console.api.SaasConsoleApi;
import io.tieringkv.saas.commerce.Subscription;

import java.util.List;

/** SaaS 控制台 UI（ADR-0150）：仪表盘/市场/订阅视图 + RBAC。 */
public final class SaasConsoleUiService {

    /** 渲染结果：HTTP 状态 + 标题 + HTML。 */
    public record Page(int status, String title, String html) {
    }

    private final SaasConsoleApi api;

    public SaasConsoleUiService(SaasConsoleApi api) {
        this.api = api;
    }

    public Page render(String token, String view) {
        return switch (view) {
            case "dashboard" -> dashboard(token);
            case "marketplace" -> marketplace(token);
            case "subscriptions" -> subscriptions(token);
            default -> new Page(404, "Not Found",
                    "<h1>404 Not Found</h1>");
        };
    }

    private Page dashboard(String token) {
        try {
            List<String> tenants = api.subscriptions(token);
            StringBuilder rows = new StringBuilder();
            for (String tenantId : tenants) {
                Subscription.Snapshot snapshot =
                        api.status(token, tenantId);
                rows.append("<tr><td>").append(escape(tenantId))
                        .append("</td><td>").append(snapshot.state())
                        .append("</td><td>").append(snapshot.cycle())
                        .append("</td></tr>");
            }
            String body = "<h1>SaaS Dashboard</h1>"
                    + "<p>Active subscriptions: " + tenants.size()
                    + "</p><table><tr><th>Tenant</th><th>State</th>"
                    + "<th>Cycle</th></tr>" + rows + "</table>";
            return page("SaaS Dashboard", body);
        } catch (SecurityException e) {
            return forbidden();
        }
    }

    private Page marketplace(String token) {
        try {
            List<String> items = api.marketplace(token);
            StringBuilder rows = new StringBuilder();
            for (String item : items) {
                rows.append("<li>").append(escape(item))
                        .append("</li>");
            }
            String body = "<h1>Marketplace</h1><ul>" + rows + "</ul>"
                    + "<form action=\"/api/subscribe\" method=\"post\">"
                    + "<input name=\"tenantId\" placeholder=\"tenant\"/>"
                    + "<input name=\"planId\" placeholder=\"plan\"/>"
                    + "<button>Subscribe</button></form>";
            return page("Marketplace", body);
        } catch (SecurityException e) {
            return forbidden();
        }
    }

    private Page subscriptions(String token) {
        try {
            List<String> tenants = api.subscriptions(token);
            StringBuilder rows = new StringBuilder();
            for (String tenantId : tenants) {
                Subscription.Snapshot snapshot =
                        api.status(token, tenantId);
                rows.append("<tr><td>").append(escape(tenantId))
                        .append("</td><td>").append(escape(
                                snapshot.planId()))
                        .append("</td><td>").append(snapshot.state())
                        .append("</td><td>").append(snapshot.cycle())
                        .append("</td></tr>");
            }
            String body = "<h1>Subscriptions</h1><table>"
                    + "<tr><th>Tenant</th><th>Plan</th><th>State</th>"
                    + "<th>Cycle</th></tr>" + rows + "</table>";
            return page("Subscriptions", body);
        } catch (SecurityException e) {
            return forbidden();
        }
    }

    private static Page page(String title, String body) {
        return new Page(200, title,
                "<!DOCTYPE html><html><head><title>"
                        + escape(title) + "</title></head><body>"
                        + body + "</body></html>");
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
