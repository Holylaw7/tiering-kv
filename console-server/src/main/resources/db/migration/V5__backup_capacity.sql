-- G0-4：备份容量预留（ADR-0369）
CREATE TABLE IF NOT EXISTS backup_capacity (
    storage_key VARCHAR(512) NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (storage_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '容量预留锁锚点';

CREATE TABLE IF NOT EXISTS backup_reservation (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    instance_id      BIGINT       NOT NULL,
    backup_record_id BIGINT       NULL,
    storage_key      VARCHAR(512) NOT NULL,
    reserved_bytes   BIGINT       NOT NULL,
    used_bytes       BIGINT       NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED/RELEASED/EXPIRED',
    created_at       DATETIME(3)  NOT NULL,
    expires_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_backup_reservation_storage (storage_key, status),
    KEY idx_backup_reservation_record (backup_record_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '备份容量预留';
