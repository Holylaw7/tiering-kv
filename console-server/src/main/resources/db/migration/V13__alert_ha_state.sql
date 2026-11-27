-- Wave 2A (H2)：告警持续时长与 Webhook 静默/防重发状态持久化表（支持多副本原子并发控制与故障转移无缝恢复）
CREATE TABLE alert_eval_state (
    state_key        VARCHAR(128) NOT NULL PRIMARY KEY,
    satisfied_since  DATETIME(3)  NULL,
    last_notified_at DATETIME(3)  NULL,
    updated_at       DATETIME(3)  NOT NULL,
    INDEX idx_alert_eval_state_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
