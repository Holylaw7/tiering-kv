package io.tieringkv.observability;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏（ADR-0263）：凭据/密钥/token/连接串密码在任何日志
 * 输出前统一替换为 ***。
 */
public final class Redactor {

    private static final String MASK = "***";
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile(
                    "(?i)(password|passwd|pwd|secret|token|"
                            + "api[_-]?key|access[_-]?key|"
                            + "key|credential)\\s*=\\s*"
                            + "([^&\\s]+)"),
            Pattern.compile(
                    "(?i)(authorization\\s*[:=]\\s*"
                            + "(?:bearer|basic|digest)\\s+)"
                            + "(\\S+)"),
            Pattern.compile("(://[^:/\\s@]+:)([^@\\s]+)(@)"));

    private Redactor() {
    }

    /** 脱敏文本：匹配到的凭据值替换为 ***。 */
    public static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = text;
        for (Pattern pattern : SECRET_PATTERNS) {
            masked = pattern.matcher(masked).replaceAll(
                    matcher -> {
                        if (matcher.groupCount() >= 2
                                && matcher.group(2) != null) {
                            return matcher.group(1) + MASK;
                        }
                        return matcher.group(0);
                    });
        }
        return masked;
    }

    /** URL 脱敏：user:pass@ 形式。 */
    public static String maskUrl(String url) {
        return mask(url);
    }

    /** 凭据整体脱敏。 */
    public static String maskCredential(String credential) {
        return credential == null || credential.isBlank()
                ? credential : MASK;
    }

    /** 检查文本是否泄露了明文凭据（测试与门禁用）。 */
    public static boolean containsSecret(String text,
                                         String secret) {
        return text != null && secret != null
                && !secret.isBlank()
                && text.contains(secret);
    }
}
