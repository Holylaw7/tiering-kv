# 代码评审（Code Review）

状态：已有 Phase 1 记录

记录每次代码审查的对象、发现与修复情况。
规则：.codex/CODE_REVIEW_RULES.md。

## Phase 1 审查记录（2026-08-09）

- 问题 1：`ByteProcessor` lambda 语义写反（返回 true 表示继续扫描），导致解码器
  对合法输入误判协议错误；已修复并补充解码器用例。
- 问题 2：RESP Encoder 置于 CommandHandler 之后，出站事件不经过编码器，客户端
  收不到响应；已调整管道顺序（encoder → decoder → handler）。
- 结论：修复后 47 用例全绿，延迟基线 P50=0.064ms。
