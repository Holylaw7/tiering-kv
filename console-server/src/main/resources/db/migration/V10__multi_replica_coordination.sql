-- M3：控制面多副本无状态化协调表（租约锁 + 共享登录限流窗口）
CREATE TABLE console_lease (
    lease_key   VARCHAR(128) NOT NULL PRIMARY KEY,
    owner_id    VARCHAR(64)  NOT NULL,
    lease_until DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    INDEX idx_console_lease_until (lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE login_attempt (
    bucket_key   VARCHAR(160) NOT NULL PRIMARY KEY,
    fail_count   INT          NOT NULL DEFAULT 0,
    window_start DATETIME(3)  NOT NULL,
    expires_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    INDEX idx_login_attempt_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
