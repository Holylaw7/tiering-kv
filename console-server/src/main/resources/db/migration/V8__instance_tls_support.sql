-- O2-B：控制面至数据面 RESP TLS 链路支持
ALTER TABLE db_instance
    ADD COLUMN tls_enabled         BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否启用 TLS 加密连接' AFTER enabled,
    ADD COLUMN tls_verify_hostname BOOLEAN NOT NULL DEFAULT TRUE  COMMENT '是否校验证书主机名' AFTER tls_enabled,
    ADD COLUMN tls_ca_cert         TEXT    NULL                   COMMENT '自定义 CA 根证书（PEM），为空则使用系统信任库' AFTER tls_verify_hostname;
