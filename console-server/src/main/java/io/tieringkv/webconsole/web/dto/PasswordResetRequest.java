package io.tieringkv.webconsole.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 重置口令请求。 */
public record PasswordResetRequest(
        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6-64 之间")
        String password) {
}
