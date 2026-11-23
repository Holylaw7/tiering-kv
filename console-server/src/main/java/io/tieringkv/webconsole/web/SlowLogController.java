package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.service.SlowLogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 慢查询日志接口（N2-A）。 */
@RestController
@RequestMapping("/api/v1/slowlog")
public class SlowLogController {

    private final SlowLogService slowLogService;

    public SlowLogController(SlowLogService slowLogService) {
        this.slowLogService = slowLogService;
    }

    @GetMapping
    public ApiResponse<SlowLogService.SlowLogView> list(
            @RequestParam Long instanceId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(slowLogService.list(instanceId, limit));
    }

    @PostMapping("/reset")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> reset(@RequestParam Long instanceId) {
        slowLogService.reset(instanceId);
        return ApiResponse.ok();
    }
}
