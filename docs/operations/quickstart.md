# Tiering-KV Quickstart

## 5 分钟上手

1. 构建：

```bash
mvn -q package
```

2. 启动单机服务：

```bash
java -jar target/tiering-kv-3.7.0-SNAPSHOT.jar --nodeId n1 --port 6379
```

3. 用 redis-cli 验证：

```bash
redis-cli PING          # PONG
redis-cli SET k v       # OK
redis-cli GET k         # v
redis-cli HSET h f 1    # 1
redis-cli HGETALL h     # f / 1
```

## 配置

见 config/tiering-kv.yaml（端口、分片、内存、WAL、水位）。

## 下一步

- 运维：docs/operations/operations-runbook.md
- 命令参考：docs/design/command-family-design.md
- 兼容性：docs/protocol/resp2-compatibility-matrix.md
