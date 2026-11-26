package io.tieringkv.webconsole.web.dto;

import io.tieringkv.webconsole.domain.BackupScheduleMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 创建/更新定时备份策略请求体（v6.1.0 A2）。 */
public record BackupScheduleRequest(
        @NotNull(message = "目标实例不能为空")
        Long instanceId,
        @NotBlank(message = "策略名称不能为空")
        @Size(max = 128, message = "策略名称长度不能超过 128")
        String name,
        @NotBlank(message = "Cron 表达式不能为空")
        @Size(max = 64, message = "Cron 表达式长度不能超过 64")
        String cronExpression,
        BackupScheduleMode mode,
        @Min(value = 1, message = "保留份数至少为 1")
        @Max(value = 365, message = "保留份数不能超过 365")
        Integer retentionCount,
        Boolean enabled) {
}
