package io.tieringkv.webconsole.web.dto;

import io.tieringkv.webconsole.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 新建用户请求。 */
public record UserCreateRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 64, message = "用户名长度需在 3-64 之间")
        String username,
        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6-64 之间")
        String password,
        @Size(max = 64, message = "昵称过长")
        String nickname,
        @Email(message = "邮箱格式不正确")
        String email,
        @NotNull(message = "角色不能为空")
        Role role) {
}
