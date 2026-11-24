package io.tieringkv.webconsole.web.dto;

import io.tieringkv.webconsole.domain.AlertChannel;

import java.time.LocalDateTime;

/** O3-B：告警渠道脱敏视图（不回传签名密钥）。 */
public record AlertChannelView(
        Long id,
        String name,
        String type,
        String url,
        boolean hasSecret,
        int silenceSeconds,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AlertChannelView of(AlertChannel channel) {
        return new AlertChannelView(
                channel.getId(),
                channel.getName(),
                channel.getType(),
                channel.getUrl(),
                channel.getSecret() != null && !channel.getSecret().isBlank(),
                channel.getSilenceSeconds(),
                channel.isEnabled(),
                channel.getCreatedAt(),
                channel.getUpdatedAt()
        );
    }
}
