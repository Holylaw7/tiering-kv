package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.service.AlertService;
import io.tieringkv.webconsole.service.InstanceService;
import io.tieringkv.webconsole.service.MetricService;
import io.tieringkv.webconsole.service.MonitorService;
import io.tieringkv.webconsole.web.dto.InstanceView;
import io.tieringkv.webconsole.web.dto.LiveMetrics;
import io.tieringkv.webconsole.web.dto.OverviewView;
import io.tieringkv.webconsole.web.dto.TrendPoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 仪表盘接口：总览 + 趋势。 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final InstanceService instanceService;
    private final MonitorService monitorService;
    private final MetricService metricService;
    private final AlertService alertService;

    public DashboardController(InstanceService instanceService, MonitorService monitorService,
                               MetricService metricService, AlertService alertService) {
        this.instanceService = instanceService;
        this.monitorService = monitorService;
        this.metricService = metricService;
        this.alertService = alertService;
    }

    @GetMapping("/overview")
    public ApiResponse<OverviewView> overview(@RequestParam(required = false) Long instanceId) {
        List<InstanceView> instances = instanceService.list();
        if (instances.isEmpty()) {
            return ApiResponse.ok(new OverviewView(List.of(), null, null, 0,
                    alertService.firingCount(), alertService.resolvedTodayCount(), 0));
        }
        Long targetId = instanceId != null ? instanceId : instances.get(0).id();
        DbInstance instance = instanceService.require(targetId);
        LiveMetrics live = monitorService.live(instance).orElse(null);
        return ApiResponse.ok(new OverviewView(instances, InstanceView.of(instance), live,
                live == null ? 0 : live.totalKeys(),
                alertService.firingCount(), alertService.resolvedTodayCount(),
                live == null ? 0 : live.uptimeSeconds()));
    }

    @GetMapping("/trends")
    public ApiResponse<List<TrendPoint>> trends(@RequestParam Long instanceId,
                                                @RequestParam(defaultValue = "30") int minutes) {
        if (minutes <= 0) {
            throw BusinessException.badRequest("minutes 必须为正数");
        }
        return ApiResponse.ok(metricService.trends(instanceId, minutes));
    }
}
