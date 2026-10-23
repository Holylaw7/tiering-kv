package io.tieringkv.webconsole.web.dto;

import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.User;

import java.time.LocalDateTime;

/** 用户视图（不含口令散列）。 */
public record UserView(Long id, String username, String nickname, String email,
                       Role role, int status, LocalDateTime lastLoginAt,
                       LocalDateTime createdAt) {

    public static UserView of(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getNickname(),
                user.getEmail(), user.getRole(), user.getStatus(),
                user.getLastLoginAt(), user.getCreatedAt());
    }
}
