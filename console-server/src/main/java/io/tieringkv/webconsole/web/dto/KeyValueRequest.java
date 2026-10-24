package io.tieringkv.webconsole.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 键值写入请求。 */
public record KeyValueRequest(
        @NotBlank(message = "键不能为空") String key,
        @NotNull(message = "值不能为空") String value) {
}
