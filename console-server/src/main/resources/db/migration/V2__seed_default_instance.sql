-- 默认纳管本机 6379 实例（幂等）
INSERT INTO db_instance (name, host, port, password, status, remark, enabled, created_at, updated_at)
SELECT 'local-6379', '127.0.0.1', 6379, NULL, 'UNKNOWN', '默认本机实例', 1, NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM db_instance WHERE name = 'local-6379');
