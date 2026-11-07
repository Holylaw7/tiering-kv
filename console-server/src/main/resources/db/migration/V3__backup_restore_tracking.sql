-- G0-1：恢复状态追踪（ADR-0366）
-- 恢复不再借用备份记录的 status，避免"恢复失败"污染"备份是否有效"这一事实。
ALTER TABLE backup_record
    ADD COLUMN restore_status    VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/RESTORING/SUCCEEDED/FAILED' AFTER message,
    ADD COLUMN restore_started_at DATETIME(3) NULL AFTER restore_status,
    ADD COLUMN restore_finished_at DATETIME(3) NULL AFTER restore_started_at,
    ADD COLUMN restored_keys      BIGINT NOT NULL DEFAULT 0 COMMENT '已回放键数（失败时为部分值）' AFTER restore_finished_at,
    ADD COLUMN restore_message    VARCHAR(255) NULL AFTER restored_keys;

CREATE INDEX idx_backup_record_instance_restore ON backup_record (instance_id, restore_status);
