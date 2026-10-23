package io.tieringkv.webconsole.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 终端执行请求。 */
public record TerminalRequest(
        @NotNull(message = "实例不能为空") Long instanceId,
        @NotBlank(message = "命令不能为空")
        @Size(max = 512, message = "命令过长")
        String command) {
}
