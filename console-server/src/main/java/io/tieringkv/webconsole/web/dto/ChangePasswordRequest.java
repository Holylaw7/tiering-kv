package io.tieringkv.webconsole.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 自助修改口令请求。 */
public record ChangePasswordRequest(
        @NotBlank(message = "原口令不能为空")
        @Size(max = 128, message = "原口令过长")
        String oldPassword,
        @NotBlank(message = "新口令不能为空")
        @Size(min = 6, max = 64, message = "新口令长度需在 6-64 之间")
        String newPassword) {
}
