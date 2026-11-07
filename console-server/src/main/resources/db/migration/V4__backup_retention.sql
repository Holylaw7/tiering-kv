-- G0-2：备份保留策略与安全删除（ADR-0367）
ALTER TABLE db_instance
    ADD COLUMN backup_retention INT NULL COMMENT '保留的最近成功备份数量，NULL 表示用全局默认' AFTER remark;

ALTER TABLE backup_record
    ADD COLUMN delete_state     VARCHAR(16) NULL COMMENT 'NULL/RETIRING：清理认领状态' AFTER restore_message,
    ADD COLUMN delete_claimed_at DATETIME(3) NULL AFTER delete_state;

CREATE INDEX idx_backup_record_delete_state ON backup_record (delete_state);
