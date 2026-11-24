package io.tieringkv.webconsole.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** O3-B：创建/更新告警通知渠道请求。 */
public record AlertChannelRequest(
        @NotBlank(message = "渠道名称不能为空")
        @Size(max = 64, message = "渠道名称过长")
        String name,
        @Size(max = 16, message = "渠道类型过长")
        String type,
        @NotBlank(message = "Webhook URL 不能为空")
        @Size(max = 512, message = "Webhook URL 过长")
        String url,
        @Size(max = 256, message = "签名密钥过长")
        String secret,
        @Min(value = 0, message = "静默秒数不能为负")
        @Max(value = 86400, message = "静默秒数不能超过 86400")
        Integer silenceSeconds,
        Boolean enabled) {
}
