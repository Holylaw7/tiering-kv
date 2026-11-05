package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.service.SystemInfoService;
import io.tieringkv.webconsole.service.TieringTableService;
import io.tieringkv.webconsole.web.dto.SstableTable;
import io.tieringkv.webconsole.web.dto.SystemInfoView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 系统信息（只读，TD-092）。 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final SystemInfoService systemInfoService;
    private final TieringTableService tieringTableService;

    public SystemController(SystemInfoService systemInfoService,
                            TieringTableService tieringTableService) {
        this.systemInfoService = systemInfoService;
        this.tieringTableService = tieringTableService;
    }

    /** 冷层 SSTable 清单（TD-099）：按需读取 `INFO sstables`。 */
    @GetMapping("/tables")
    public ApiResponse<SstableTable.View> tables(@RequestParam Long instanceId) {
        return ApiResponse.ok(tieringTableService.list(instanceId));
    }

    @GetMapping("/info")
    public ApiResponse<SystemInfoView> info(@RequestParam Long instanceId) {
        return ApiResponse.ok(systemInfoService.snapshot(instanceId));
    }
}