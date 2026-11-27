-- v6.4.0 Stage D1/D2：备份归档完整性校验与恢复保护点 + 审计请求链路关联 ID
ALTER TABLE backup_record
    ADD COLUMN sha256                CHAR(64)    NULL COMMENT '归档字节流 SHA-256 摘要',
    ADD COLUMN verify_status         VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED' COMMENT 'UNVERIFIED/VERIFIED/CORRUPTED',
    ADD COLUMN verified_at           DATETIME(3) NULL COMMENT '最近完整性校验时间',
    ADD COLUMN pre_restore_backup_id BIGINT      NULL COMMENT '恢复前自动生成的安全保护快照 ID';

ALTER TABLE op_log
    ADD COLUMN request_id VARCHAR(64) NULL COMMENT '请求链路关联 ID（X-Request-Id / traceparent）';

CREATE INDEX idx_op_log_request_id ON op_log (request_id);
