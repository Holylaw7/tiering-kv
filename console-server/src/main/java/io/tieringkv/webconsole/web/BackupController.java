package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.domain.BackupRecord;
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

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
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
        Path file = backupService.fileOf(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        backupService.delete(id);
        return ApiResponse.ok();
    }
}
