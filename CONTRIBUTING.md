# Contributing

## 环境

- JDK 17+，Maven 3.9+；
- 构建验证：`mvn -B clean verify`。

## 工作流

1. 阅读 .codex/AGENT_CONTEXT.md 与 ROADMAP.md，确认当前阶段；
2. 从 develop 拉取 `feature/<module>` 分支；
3. 按 TDD 顺序：接口 → 测试 → 实现；
4. 架构级变更先创建 ADR；
5. 提交使用 Conventional Commit；
6. 对照 .codex/CODE_REVIEW_RULES.md 自审并请求审查；
7. 合并回 develop，阶段完成同步文档并提交。

## 测试要求

- 核心模块：单元 + 集成 + 压力测试；
- 提交前 `mvn test` 必须通过。

## 文档同步

代码变更必须同步 README / Architecture / ADR / CHANGELOG，禁止代码与文档不一致。
