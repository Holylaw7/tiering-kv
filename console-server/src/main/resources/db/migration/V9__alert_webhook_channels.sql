-- O3-B：告警外部 Webhook 通知渠道（含签名密钥与静默窗口）
CREATE TABLE alert_channel (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL UNIQUE,
    type            VARCHAR(16)  NOT NULL DEFAULT 'WEBHOOK' COMMENT 'WEBHOOK',
    url             VARCHAR(512) NOT NULL,
    secret          VARCHAR(512) NULL COMMENT 'HMAC-SHA256 签名密钥（支持静态加密）',
    silence_seconds INT          NOT NULL DEFAULT 300 COMMENT '同规则同实例重复触发静默秒数',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
