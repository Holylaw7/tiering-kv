package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.service.BackupCapacityService;
import io.tieringkv.webconsole.service.BackupRetentionService;
import io.tieringkv.webconsole.service.BackupService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

/** 备份与恢复接口。 */
@RestController
@RequestMapping("/api/v1/backups")
public class BackupController {

    private final BackupService backupService;
    private final BackupRetentionService retentionService;
    private final BackupCapacityService capacityService;

    public BackupController(BackupService backupService, BackupRetentionService retentionService,
                            BackupCapacityService capacityService) {
        this.backupService = backupService;
        this.retentionService = retentionService;
        this.capacityService = capacityService;
    }

    @GetMapping
    public ApiResponse<List<BackupRecord>> list(@RequestParam(required = false) Long instanceId) {
        return ApiResponse.ok(backupService.list(instanceId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<BackupRecord> create(@RequestParam Long instanceId) {
        return ApiResponse.ok(backupService.export(instanceId));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Long> restore(@PathVariable Long id) {
        return ApiResponse.ok(backupService.restore(id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Path file = backupService.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    /** 手工触发一次保留清理（ADR-0367）。 */
    @PostMapping("/purge")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BackupRetentionService.PurgeReport> purge() {
        return ApiResponse.ok(retentionService.purge());
    }

    /** 磁盘容量视图（ADR-0369）。 */
    @GetMapping("/capacity")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<BackupCapacityService.CapacityView> capacity() {
        return ApiResponse.ok(capacityService.view());
    }

    /** 保留策略与上次清理结果。 */
    @GetMapping("/retention")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<BackupRetentionService.RetentionView> retention() {
        return ApiResponse.ok(retentionService.view());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        retentionService.delete(id);
        return ApiResponse.ok();
    }
}