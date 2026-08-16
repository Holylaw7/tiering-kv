# 测试度量口径（Test Measurement Conventions）

状态：Accepted（2026-08-16）

## 问题

仓库与简历中常出现“全量测试 14950 项”。由于 JUnit 5 / Maven Surefire
的统计方式，`@ParameterizedTest` 的每一组参数都会产生**一次独立执行**
（invocation），因此必须明确区分三个概念，避免把“执行次数”误写成
“测试方法数”或“独立场景数”。

## 定义

| 术语 | 定义 | 本仓库实测（v4.1.0 收尾，2026-08-16） |
| --- | --- | --- |
| **Test Methods（测试方法数）** | 源码中声明的测试方法：`@Test` + `@ParameterizedTest` + `@RepeatedTest` + `@TestFactory` | **约 6,736 个**（5104 个 `@Test` + 1632 个 `@ParameterizedTest`；无 `@RepeatedTest`/`@TestFactory`） |
| **Test Executions / Invocations（测试执行次数）** | JUnit 平台实际执行的测试节点数，即 Surefire “Tests run” 与报告 XML 中 `<testcase>` 元素数；每个参数化参数组合计为一次 | **14,950 次**（最近一次全量回归，0 failures，13 skipped） |
| **Test Scenarios（独立场景）** | 按业务语义人工归类的场景数（如“SET 后 TTL 正确”为一个场景）；不提供机械统计 | 不定义机械口径 |

## 结论与使用约定

1. 项目文档、简历、报告中的“**14950**”一律指 **Test Executions
   （Surefire Tests run 执行次数，含参数化 invocation）**；
2. 不得写作“14950 个独立测试场景”或“14950 个测试方法”；
3. 需要强调方法级规模时使用“**约 6,736 个测试方法**”（含
   `@ParameterizedTest` 方法本身）；
4. 参数化放大倍率：14,950 / 6,736 ≈ **2.2x**（主要来自
   `@ParameterizedTest` 参数组合展开）；
5. 校验方法：`mvn -Dsurefire.excludedGroups=benchmark test` 后统计
   `target/surefire-reports/*.xml` 的 `<testcase>` 总数，应与
   “Tests run” 汇总一致。

## 推荐表述

- ✅ **双指标表述（对外首选）**：“**约 6,736 个测试方法，累计
  **14,950 次测试执行**（Surefire Tests run，含参数化 invocation），
  0 failures”；
- ✅ “全量测试执行 **14,950 次**（Surefire Tests run，含参数化
  invocation），0 failures”；
- ❌ “14,950 个独立测试场景”
- ❌ “14,950 个测试用例（用例=方法）”
