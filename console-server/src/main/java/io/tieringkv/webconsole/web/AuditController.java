package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.common.PageResult;
import io.tieringkv.webconsole.domain.OpLog;
import io.tieringkv.webconsole.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计日志查询。 */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<PageResult<OpLog>> page(
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<OpLog> result = auditService.page(action, page, size);
        return ApiResponse.ok(PageResult.of(result.getContent(),
                result.getTotalElements(), page, size));
    }
}
