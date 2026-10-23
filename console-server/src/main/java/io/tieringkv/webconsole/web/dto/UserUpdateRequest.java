package io.tieringkv.webconsole.web.dto;

import io.tieringkv.webconsole.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** 更新用户请求（空值表示不修改）。 */
public record UserUpdateRequest(
        @Size(max = 64, message = "昵称过长") String nickname,
        @Email(message = "邮箱格式不正确") String email,
        Role role,
        Integer status) {
}
