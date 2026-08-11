# 协议版本定义

Phase 26 · ADR-0103

```text
RPC_VERSION               = 1
RESP_VERSION              = 2
STORAGE_FORMAT_VERSION    = 1
META_COMMAND_VERSION      = 1
```

实现：`io.tieringkv.protocol.ProtocolVersion`。

版本策略：

1. additive 变更（新命令/新事件类型）不升主版本；
2. 破坏性变更必须升版本号并保留旧版本解析路径（v1 兼容层）；
3. 存储格式变化必须可迁移（升级工具 + 兼容性测试）。
