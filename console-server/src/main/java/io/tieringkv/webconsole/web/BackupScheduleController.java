package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.service.BackupScheduleService;
import io.tieringkv.webconsole.web.dto.BackupScheduleRequest;
import io.tieringkv.webconsole.web.dto.BackupScheduleRunResult;
import io.tieringkv.webconsole.web.dto.BackupScheduleView;
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

/** 定时备份策略接口（v6.1.0 A2）。 */
@RestController
@RequestMapping("/api/v1/backups/schedules")
public class BackupScheduleController {

    private final BackupScheduleService scheduleService;

    public BackupScheduleController(BackupScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping
    public ApiResponse<List<BackupScheduleView>> list(@RequestParam(required = false) Long instanceId) {
        return ApiResponse.ok(scheduleService.list(instanceId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<BackupScheduleView> create(@Valid @RequestBody BackupScheduleRequest request) {
        return ApiResponse.ok(scheduleService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<BackupScheduleView> update(@PathVariable Long id,
                                                  @Valid @RequestBody BackupScheduleRequest request) {
        return ApiResponse.ok(scheduleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        scheduleService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/run-now")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<BackupScheduleRunResult> runNow(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.runNow(id));
    }
}
