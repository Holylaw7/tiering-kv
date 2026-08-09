# Task: Phase 0 — 工程初始化

状态：✅ 已完成（2026-08-09）

## 目标

完成仓库初始化：Git、目录骨架、工程控制中心、知识库、构建与 CI 骨架。

## 交付物

- Git 仓库（main / develop），语义化提交；
- `.codex/` 工程控制中心（MASTER_PROMPT / DEVELOPMENT_RULES / AGENT_CONTEXT /
  CODE_REVIEW_RULES / RELEASE_RULES / tasks）；
- docs 知识库（requirements / architecture / adr / design / benchmark / review /
  operations）；
- src / tests / benchmarks / scripts / config / examples / tools 骨架；
- Maven 骨架（Java 17、JUnit 5）与 CI 工作流；
- ADR-0001 ~ ADR-0005。

## 验收

- `mvn test` 通过；
- git log 为 Conventional Commit；
- 仓库布局与框架标准一致。
