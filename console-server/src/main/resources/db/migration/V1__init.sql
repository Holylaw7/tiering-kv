-- Tiering-KV Web 控制台初始库表（MySQL 8 / utf8mb4）
-- 设计说明见 docs/design/console-data-model.md

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名',
    password      VARCHAR(100) NOT NULL COMMENT 'BCrypt 口令散列',
    nickname      VARCHAR(64)  NULL COMMENT '显示名',
    email         VARCHAR(128) NULL COMMENT '邮箱',
    role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN/OPS/VIEWER',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 禁用',
    last_login_at DATETIME(3)  NULL COMMENT '最近登录时间',
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '平台用户';

CREATE TABLE IF NOT EXISTS op_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NULL,
    username   VARCHAR(64)  NULL,
    action     VARCHAR(64)  NOT NULL COMMENT '动作，如 KEY_DELETE',
    target     VARCHAR(255) NULL COMMENT '作用对象',
    detail     TEXT         NULL,
    ip         VARCHAR(64)  NULL,
    success    TINYINT      NOT NULL DEFAULT 1,
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_op_log_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '操作审计日志';

CREATE TABLE IF NOT EXISTS db_instance (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(64)  NOT NULL COMMENT '实例别名',
    host          VARCHAR(128) NOT NULL,
    port          INT          NOT NULL,
    password      VARCHAR(128) NULL COMMENT 'AUTH 口令，可为空',
    status        VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UP/DOWN/UNKNOWN',
    version       VARCHAR(32)  NULL COMMENT '探测到的内核版本',
    last_probe_at DATETIME(3)  NULL,
    remark        VARCHAR(255) NULL,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_db_instance_name (name),
    UNIQUE KEY uk_db_instance_addr (host, port)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '被纳管的 Tiering-KV 实例';

CREATE TABLE IF NOT EXISTS metric_point (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    instance_id     BIGINT      NOT NULL,
    collected_at    DATETIME(3) NOT NULL,
    qps             DOUBLE      NULL,
    ops_per_sec     DOUBLE      NULL,
    avg_latency_ms  DOUBLE      NULL,
    connections     INT         NULL,
    active_requests INT         NULL,
    errors          BIGINT      NULL,
    used_memory     BIGINT      NULL,
    max_memory      BIGINT      NULL,
    total_keys      BIGINT      NULL,
    uptime_seconds  BIGINT      NULL,
    PRIMARY KEY (id),
    KEY idx_metric_point_instance_time (instance_id, collected_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '时序指标采样点';

CREATE TABLE IF NOT EXISTS alert_rule (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(64)  NOT NULL,
    instance_id      BIGINT       NULL COMMENT 'NULL 表示全部实例',
    metric           VARCHAR(32)  NOT NULL COMMENT 'qps/avg_latency_ms/used_memory_ratio/errors/connections/total_keys',
    operator         VARCHAR(4)   NOT NULL COMMENT 'GT/LT',
    threshold        DOUBLE       NOT NULL,
    duration_seconds INT          NOT NULL DEFAULT 0 COMMENT '连续满足时长',
    level            VARCHAR(8)   NOT NULL DEFAULT 'WARN' COMMENT 'WARN/CRIT',
    enabled          TINYINT      NOT NULL DEFAULT 1,
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_rule_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '告警规则';

CREATE TABLE IF NOT EXISTS alert_event (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    rule_id      BIGINT       NOT NULL,
    instance_id  BIGINT       NOT NULL,
    rule_name    VARCHAR(64)  NOT NULL,
    metric       VARCHAR(32)  NOT NULL,
    value        DOUBLE       NOT NULL,
    threshold    DOUBLE       NOT NULL,
    level        VARCHAR(8)   NOT NULL,
    message      VARCHAR(255) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'FIRING' COMMENT 'FIRING/RESOLVED',
    fired_at     DATETIME(3)  NOT NULL,
    resolved_at  DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_alert_event_status (status, fired_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '告警事件';

CREATE TABLE IF NOT EXISTS backup_record (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    instance_id BIGINT       NOT NULL,
    file_name   VARCHAR(128) NOT NULL,
    size_bytes  BIGINT       NOT NULL DEFAULT 0,
    key_count   BIGINT       NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED',
    message     VARCHAR(255) NULL,
    created_at  DATETIME(3)  NOT NULL,
    finished_at DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_backup_record_instance (instance_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '逻辑备份记录';

CREATE TABLE IF NOT EXISTS terminal_history (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NULL,
    username    VARCHAR(64)  NULL,
    instance_id BIGINT       NOT NULL,
    command     VARCHAR(512) NOT NULL,
    success     TINYINT      NOT NULL DEFAULT 1,
    cost_ms     BIGINT       NOT NULL DEFAULT 0,
    reply_type  VARCHAR(16)  NULL,
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_terminal_history_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '在线终端命令历史';
