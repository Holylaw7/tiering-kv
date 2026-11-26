package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.service.ClusterTopologyService;
import io.tieringkv.webconsole.service.K8sOperatorBridgeService;
import io.tieringkv.webconsole.web.dto.ClusterDtos.ClusterTopologyView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sBackupTriggerResult;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sClusterListView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sClusterSummary;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sScaleRequest;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sUpgradeRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 分布式集群拓扑与 K8s Operator 桥接接口（v6.2.0 C2）。 */
@RestController
@RequestMapping("/api/v1/cluster")
public class ClusterController {

    private final ClusterTopologyService topologyService;
    private final K8sOperatorBridgeService k8sBridgeService;

    public ClusterController(ClusterTopologyService topologyService,
                             K8sOperatorBridgeService k8sBridgeService) {
        this.topologyService = topologyService;
        this.k8sBridgeService = k8sBridgeService;
    }

    @GetMapping("/topology")
    public ApiResponse<ClusterTopologyView> topology(@RequestParam Long instanceId) {
        return ApiResponse.ok(topologyService.inspect(instanceId));
    }

    @GetMapping("/k8s/clusters")
    public ApiResponse<K8sClusterListView> k8sClusters() {
        return ApiResponse.ok(k8sBridgeService.listClusters());
    }

    @PostMapping("/k8s/clusters/{name}/scale")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<K8sClusterSummary> scaleCluster(@PathVariable String name,
                                                       @Valid @RequestBody K8sScaleRequest request) {
        return ApiResponse.ok(k8sBridgeService.scaleCluster(name, request));
    }

    @PostMapping("/k8s/clusters/{name}/upgrade")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<K8sClusterSummary> upgradeCluster(@PathVariable String name,
                                                         @Valid @RequestBody K8sUpgradeRequest request) {
        return ApiResponse.ok(k8sBridgeService.upgradeCluster(name, request));
    }

    @PostMapping("/k8s/clusters/{name}/backup")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<K8sBackupTriggerResult> triggerBackup(@PathVariable String name) {
        return ApiResponse.ok(k8sBridgeService.triggerBackup(name));
    }
}
