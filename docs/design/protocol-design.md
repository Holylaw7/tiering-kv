# 协议详细设计（Protocol Design）

状态：草稿（Phase 1 细化）

## 范围

RESP 编解码、命令注册、错误响应。

## 接口草案

```java
// 草案：Phase 1 以 TDD 形式定稿
public interface RespEncoder { byte[] encode(Object value); }
public interface RespDecoder { RespRequest decode(ByteBuf buffer); }
```

## 待定项

- RESP2 vs RESP3（Phase 1 决策）；
- 命令集合与参数校验规则；
- 批量请求（pipeline）与背压交互。
