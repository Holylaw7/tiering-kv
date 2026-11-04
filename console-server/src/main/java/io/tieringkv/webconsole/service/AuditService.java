package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.OpLog;
import io.tieringkv.webconsole.repository.OpLogRepository;
import io.tieringkv.webconsole.security.SecurityUtils;
import io.tieringkv.webconsole.security.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 审计服务：所有写操作留痕。 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** CSV 导出上限（避免一次导出拖垮服务）。 */
    private static final int EXPORT_LIMIT = 10_000;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    /** 多条件查询（TD-090）：动作/用户模糊、结果、时间区间。 */
    public Page<OpLog> search(String action, String username, Boolean success,
                              LocalDateTime from, LocalDateTime to, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(0, page - 1), Math.min(200, Math.max(1, size)));
        return repository.findAll(specification(action, username, success, from, to), request);
    }

    /** 导出 CSV（TD-090）：最多导出最近 {@value #EXPORT_LIMIT} 条。 */
    public String exportCsv(String action, String username, Boolean success,
                            LocalDateTime from, LocalDateTime to) {
        PageRequest first = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<OpLog> rows = repository
                .findAll(specification(action, username, success, from, to), first)
                .getContent();
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("时间,用户,动作,对象,详情,IP,结果\n");
        for (OpLog row : rows) {
            csv.append(csvCell(format(row.getCreatedAt()))).append(',')
                    .append(csvCell(row.getUsername())).append(',')
                    .append(csvCell(row.getAction())).append(',')
                    .append(csvCell(row.getTarget())).append(',')
                    .append(csvCell(row.getDetail())).append(',')
                    .append(csvCell(row.getIp())).append(',')
                    .append(row.isSuccess() ? "成功" : "失败").append('\n');
        }
        return csv.toString();
    }

    private Specification<OpLog> specification(String action, String username,
                                               Boolean success, LocalDateTime from,
                                               LocalDateTime to) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (action != null && !action.isBlank()) {
                predicates.add(builder.like(builder.lower(root.get("action")),
                        "%" + action.toLowerCase() + "%"));
            }
            if (username != null && !username.isBlank()) {
                predicates.add(builder.like(builder.lower(root.get("username")),
                        "%" + username.toLowerCase() + "%"));
            }
            if (success != null) {
                predicates.add(builder.equal(root.get("success"), success));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String format(LocalDateTime time) {
        return time == null ? "" : TIME_FORMAT.format(time);
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return escaped.indexOf(',') >= 0 || escaped.indexOf('\n') >= 0
                ? "\"" + escaped + "\"" : escaped;
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
