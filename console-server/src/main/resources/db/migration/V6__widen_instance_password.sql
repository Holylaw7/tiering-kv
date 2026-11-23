-- M3-B3：扩宽实例口令列，支持 AES-256-GCM 密文存储（enc:v1:...）
ALTER TABLE db_instance
    MODIFY COLUMN password VARCHAR(512) NULL COMMENT 'AUTH 口令（支持 AES-256-GCM 密文）';
