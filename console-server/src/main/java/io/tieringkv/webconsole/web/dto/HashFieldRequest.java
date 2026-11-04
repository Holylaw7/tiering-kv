package io.tieringkv.webconsole.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** hash 单字段写入请求（TD-089）。 */
public record HashFieldRequest(
        @NotBlank(message = "键不能为空")
        @Size(max = 512, message = "键过长")
        String key,
        @NotBlank(message = "字段名不能为空")
        @Size(max = 512, message = "字段名过长")
        String field,
        @Size(max = 65536, message = "字段值过长")
        String value) {
}
