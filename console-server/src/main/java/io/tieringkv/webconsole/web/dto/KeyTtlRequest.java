package io.tieringkv.webconsole.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 过期时间设置请求（ttlSeconds<=0 表示持久化）。 */
public record KeyTtlRequest(
        @NotBlank(message = "键不能为空") String key,
        @NotNull(message = "ttl 不能为空") Long ttlSeconds) {
}
