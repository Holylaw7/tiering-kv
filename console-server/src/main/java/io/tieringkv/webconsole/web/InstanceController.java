package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.service.InstanceService;
import io.tieringkv.webconsole.web.dto.InstanceRequest;
import io.tieringkv.webconsole.web.dto.InstanceView;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 实例管理接口。 */
@RestController
@RequestMapping("/api/v1/instances")
public class InstanceController {

    private final InstanceService instanceService;

    public InstanceController(InstanceService instanceService) {
        this.instanceService = instanceService;
    }

    @GetMapping
    public ApiResponse<List<InstanceView>> list() {
        return ApiResponse.ok(instanceService.list());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<InstanceView> create(@Valid @RequestBody InstanceRequest request) {
        return ApiResponse.ok(instanceService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<InstanceView> update(@PathVariable Long id,
                                            @Valid @RequestBody InstanceRequest request) {
        return ApiResponse.ok(instanceService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        instanceService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/probe")
    public ApiResponse<InstanceView> probe(@PathVariable Long id) {
        return ApiResponse.ok(instanceService.probe(id));
    }
}
