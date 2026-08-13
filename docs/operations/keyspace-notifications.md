# Keyspace Notifications

## 语义

- 惰性过期（setex 0 / expire 过去时间）与主动过期（TTLManager）
  移除成功后发布 `__keyspace@0__:<key>` expired；
- 经 PubSubBroker 本地至少一次；`KeyspaceNotifications.setEnabled`
  开关（默认开）。

## 限制

- 不落盘、不保证顺序；高频过期有通知压力。
