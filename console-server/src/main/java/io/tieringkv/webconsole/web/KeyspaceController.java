package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.service.KeyspaceService;
import io.tieringkv.webconsole.web.dto.KeyDetail;
import io.tieringkv.webconsole.web.dto.KeyScanPage;
import io.tieringkv.webconsole.web.dto.KeyTtlRequest;
import io.tieringkv.webconsole.web.dto.KeyValueRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 键空间接口：扫描、查看、编辑、删除、TTL。 */
@RestController
@RequestMapping("/api/v1/keys")
public class KeyspaceController {

    private final KeyspaceService keyspaceService;

    public KeyspaceController(KeyspaceService keyspaceService) {
        this.keyspaceService = keyspaceService;
    }

    @GetMapping("/scan")
    public ApiResponse<KeyScanPage> scan(@RequestParam Long instanceId,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) String pattern,
                                         @RequestParam(defaultValue = "50") int count) {
        return ApiResponse.ok(keyspaceService.scan(instanceId, cursor, pattern, count));
    }

    @GetMapping("/detail")
    public ApiResponse<KeyDetail> detail(@RequestParam Long instanceId,
                                         @RequestParam String key) {
        return ApiResponse.ok(keyspaceService.detail(instanceId, key));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> put(@RequestParam Long instanceId,
                                 @Valid @RequestBody KeyValueRequest request) {
        keyspaceService.setString(instanceId, request.key(), request.value());
        return ApiResponse.ok();
    }

    @PutMapping("/ttl")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> ttl(@RequestParam Long instanceId,
                                 @Valid @RequestBody KeyTtlRequest request) {
        keyspaceService.expire(instanceId, request.key(), request.ttlSeconds());
        return ApiResponse.ok();
    }

    @DeleteMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> delete(@RequestParam Long instanceId,
                                    @RequestParam String key) {
        keyspaceService.delete(instanceId, key);
        return ApiResponse.ok();
    }
}
