-- O1-B：逻辑备份导出进度追踪（与 restored_keys 共同支撑长任务进度展示）
ALTER TABLE backup_record
    ADD COLUMN processed_keys BIGINT NOT NULL DEFAULT 0 COMMENT '导出过程中已处理键数' AFTER key_count;
