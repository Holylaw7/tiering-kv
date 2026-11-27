package io.tieringkv.webconsole.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全链路请求关联过滤器（v6.4.0 D2）：
 * 优先接续合法上游 {@code X-Request-Id} 或 W3C {@code traceparent} 的 trace-id，
 * 否则生成 16 位十六进制短 ID，写入 SLF4J MDC（{@code requestId}）并回写响应头。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_TRACEPARENT = "traceparent";
    public static final String MDC_KEY = "requestId";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{4,64}$");
    private static final Pattern W3C_TRACEPARENT = Pattern.compile(
            "^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_REQUEST_ID, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolveRequestId(HttpServletRequest request) {
        String explicit = request.getHeader(HEADER_REQUEST_ID);
        if (explicit != null) {
            String trimmed = explicit.trim();
            if (SAFE_REQUEST_ID.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        String traceparent = request.getHeader(HEADER_TRACEPARENT);
        if (traceparent != null) {
            var matcher = W3C_TRACEPARENT.matcher(traceparent.trim().toLowerCase());
            if (matcher.matches()) {
                String traceId = matcher.group(1);
                if (!"00000000000000000000000000000000".equals(traceId)) {
                    return traceId;
                }
            }
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String currentRequestId() {
        String id = MDC.get(MDC_KEY);
        return id == null || id.isBlank() ? null : id;
    }
}
