package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.service.AlertChannelService;
import io.tieringkv.webconsole.web.dto.AlertChannelRequest;
import io.tieringkv.webconsole.web.dto.AlertChannelView;
import io.tieringkv.webconsole.web.dto.WebhookTestResult;
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

/** O3-B：告警 Webhook 通知渠道接口。 */
@RestController
@RequestMapping("/api/v1/alerts/channels")
public class AlertChannelController {

    private final AlertChannelService channelService;

    public AlertChannelController(AlertChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping
    public ApiResponse<List<AlertChannelView>> list() {
        return ApiResponse.ok(channelService.list());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<AlertChannelView> create(@Valid @RequestBody AlertChannelRequest request) {
        return ApiResponse.ok(channelService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<AlertChannelView> update(@PathVariable Long id,
                                                @Valid @RequestBody AlertChannelRequest request) {
        return ApiResponse.ok(channelService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        channelService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<WebhookTestResult> test(@PathVariable Long id) {
        return ApiResponse.ok(channelService.test(id));
    }
}
