package io.tieringkv.webconsole.web.dto;

import io.tieringkv.webconsole.domain.AlertLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 告警规则请求。 */
public record AlertRuleRequest(
        @NotBlank(message = "规则名称不能为空")
        @Size(max = 64, message = "规则名称过长")
        String name,
        Long instanceId,
        @NotBlank(message = "指标不能为空")
        @Pattern(regexp = "qps|ops_per_sec|avg_latency_ms|connections|active_requests|errors|used_memory|max_memory|used_memory_ratio|total_keys|uptime_seconds",
                message = "不支持的指标")
        String metric,
        @NotBlank(message = "比较符不能为空")
        @Pattern(regexp = "GT|LT", message = "比较符只能是 GT 或 LT")
        String operator,
        @NotNull(message = "阈值不能为空")
        Double threshold,
        @Min(value = 0, message = "持续时长不能为负")
        @Max(value = 3600, message = "持续时长过大")
        Integer durationSeconds,
        @NotNull(message = "级别不能为空")
        AlertLevel level,
        Boolean enabled) {
}
