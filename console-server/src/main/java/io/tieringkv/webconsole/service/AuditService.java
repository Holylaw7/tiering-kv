package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.OpLog;
import io.tieringkv.webconsole.repository.OpLogRepository;
import io.tieringkv.webconsole.security.SecurityUtils;
import io.tieringkv.webconsole.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 审计服务：所有写操作留痕。 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final OpLogRepository repository;

    public AuditService(OpLogRepository repository) {
        this.repository = repository;
    }

    public void record(String action, String target, String detail, boolean success) {
        OpLog entry = new OpLog();
        try {
            UserPrincipal principal = SecurityUtils.currentUser();
            entry.setUserId(principal.id());
            entry.setUsername(principal.username());
        } catch (BusinessException ignored) {
            entry.setUsername("anonymous");
        }
        entry.setAction(action);
        entry.setTarget(target);
        entry.setDetail(detail);
        entry.setIp(clientIp());
        entry.setSuccess(success);
        try {
            repository.save(entry);
        } catch (RuntimeException ex) {
            // 审计失败不影响主流程，但必须可观测
            log.warn("审计日志写入失败 action={} target={}", action, target, ex);
        }
    }

    public Page<OpLog> page(String action, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(0, page - 1), Math.min(200, Math.max(1, size)));
        return action == null || action.isBlank()
                ? repository.findAllByOrderByCreatedAtDesc(request)
                : repository.findByActionContainingOrderByCreatedAtDesc(action, request);
    }

    private String clientIp() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
        return "unknown";
    }
}
