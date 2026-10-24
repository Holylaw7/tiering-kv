package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.common.PageResult;
import io.tieringkv.webconsole.domain.AlertEvent;
import io.tieringkv.webconsole.domain.AlertRule;
import io.tieringkv.webconsole.service.AlertService;
import io.tieringkv.webconsole.web.dto.AlertRuleRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 告警接口。 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/rules")
    public ApiResponse<List<AlertRule>> rules() {
        return ApiResponse.ok(alertService.rules());
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<AlertRule> createRule(@Valid @RequestBody AlertRuleRequest request) {
        return ApiResponse.ok(alertService.create(request));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<AlertRule> updateRule(@PathVariable Long id,
                                             @Valid @RequestBody AlertRuleRequest request) {
        return ApiResponse.ok(alertService.update(id, request));
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        alertService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/events")
    public ApiResponse<PageResult<AlertEvent>> events(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(alertService.events(status, page, size));
    }

    @PostMapping("/events/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> resolve(@PathVariable Long id) {
        alertService.resolve(id);
        return ApiResponse.ok();
    }
}
