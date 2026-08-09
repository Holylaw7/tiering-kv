# redis-cli 演示（规划）

服务端启动后（Phase 1+）：

```text
$ redis-cli -p 6379
127.0.0.1:6379> PING
PONG
127.0.0.1:6379> SET hotkey value
OK
127.0.0.1:6379> GET hotkey
"value"
127.0.0.1:6379> DEL hotkey
(integer) 1
127.0.0.1:6379> GET missing
(nil)
```

协议与错误语义见 docs/design/protocol-design.md。
