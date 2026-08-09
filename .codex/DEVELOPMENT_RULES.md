# Tiering-KV 开发规范（DEVELOPMENT_RULES）

> 本文档由主控提示词提炼，是本仓库所有开发工作的强制规则。

## 1. 阶段流程

每个阶段严格按以下顺序执行，不得跳过或倒序：

```text
需求 → 设计 → ADR → 实现（TDD） → 测试 → 性能验证 → Git Commit
```

阶段完成必须：更新 `docs/`、创建/更新 ADR、编写测试、Git commit、
输出阶段总结报告。

## 2. 禁止事项

- 一次性生成整个项目代码；
- 跳过设计阶段直接编码；
- 修改架构但不记录原因；
- 删除已有测试绕过问题；
- 大规模重构无 Git 记录；
- 未验证代码直接提交。

## 3. ADR 规则

- 所有重要技术决策必须生成 ADR，路径 `docs/adr/`，命名
  `ADR-xxxx-title.md`；
- 模板必须包含：Status / Context / Decision / Alternatives / Consequences /
  Implementation；
- 强制场景：存储结构选择、网络模型选择、锁机制选择、IO 模型选择、
  数据淘汰算法选择、序列化协议选择、数据一致性策略、性能优化方案。

## 4. Git 管理

- 分支：`main`（稳定）← `develop`（集成）← `feature/*`；
- Commit 采用 Conventional Commit：`feat` / `fix` / `refactor` / `test` /
  `perf` / `docs` / `build` / `chore`；
- 每个阶段至少 commit 一次，禁止无意义提交（如 `update code`）；
- 修改前查看 git status，创建 checkpoint tag；失败时回滚
  `git reset --hard checkpoint-before-xxx`。

## 5. TDD 顺序

```text
先写接口 → 定义测试 → 实现 → 优化 → Benchmark
```

核心模块必须包含：单元测试、集成测试、压力测试。

## 6. 代码质量

- 清晰模块边界、接口优先、SOLID；
- 关键算法注释、避免重复代码、编写异常处理；
- 禁止：巨型 Class、魔法数字、隐式状态、无测试代码。

## 7. 文档同步

代码变化必须同步 README / Architecture / ADR / CHANGELOG；
禁止代码与文档不一致。

## 8. 任务执行格式

开始任务前输出 **Task Plan**：

```text
Goal / Design / Files affected / ADR required / Test plan / Commit message
```

完成后输出 **Completed**：

```text
Changes / Tests / Benchmark / Git Commit / Next Step
```

## 9. 会话启动仪式

每次开始工作前先读取：

```text
README.md → ROADMAP.md → CHANGELOG.md → docs/adr/ → git log
```

确认当前阶段、未完成任务与技术债后继续。
