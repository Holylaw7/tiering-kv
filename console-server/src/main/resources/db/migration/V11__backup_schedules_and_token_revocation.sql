-- v6.1.0 Stage A2：JWT 令牌版本吊销 + 定时备份策略表
ALTER TABLE sys_user
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0 COMMENT '令牌版本号，登出/改密/禁用/变更角色时递增';

CREATE TABLE IF NOT EXISTS backup_schedule (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    instance_id     BIGINT       NOT NULL COMMENT '目标实例 ID',
    name            VARCHAR(128) NOT NULL COMMENT '策略名称（全局唯一）',
    cron_expression VARCHAR(64)  NOT NULL COMMENT 'Cron 表达式（支持 5 段 Unix 或 6 段 Spring Cron）',
    mode            VARCHAR(16)  NOT NULL DEFAULT 'LOGICAL' COMMENT 'LOGICAL/CHECKPOINT',
    retention_count INT          NOT NULL DEFAULT 7 COMMENT '每实例保留最近成功备份份数',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    last_run_at     DATETIME(3)  NULL COMMENT '最近执行时间',
    next_run_at     DATETIME(3)  NULL COMMENT '下次计划执行时间',
    last_status     VARCHAR(16)  NULL COMMENT 'SUCCESS/FAILED/SKIPPED',
    last_message    VARCHAR(512) NULL COMMENT '最近执行摘要或错误原因',
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_backup_schedule_name (name),
    KEY idx_backup_schedule_due (enabled, next_run_at),
    KEY idx_backup_schedule_instance (instance_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '定时备份策略';
