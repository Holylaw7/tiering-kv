package io.tieringkv.webconsole.web.dto;

/** O3-B：Webhook 通知投递测试结果。 */
public record WebhookTestResult(
        boolean delivered,
        int statusCode,
        String message,
        long costMs) {
}
