package io.tieringkv.webconsole.web.dto;

/** 登录响应：令牌 + 当前用户。 */
public record LoginResponse(String token, long expiresInSeconds, UserView user) {
}
