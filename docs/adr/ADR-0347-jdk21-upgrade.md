# ADR-0347: JDK 21 Upgrade (Formal Adoption)

## Status

Accepted

## Context

- 项目自 Phase 0 起 `maven.compiler.release=17`；
- ADR-0331（JDK 21 虚拟线程 POC）已证明 `--virtual-threads true`
  下 941K ops/s，JDK 17 仅反射回退路径；
- release 21 产物无法运行在 JRE 17 上，容器镜像（builder 与
  runtime）必须同步升级；
- CI 全部 workflow（build/test/benchmark/release/transaction-e2e）
  当前固定 temurin 17。

## Decision

**正式升级 JDK 21**：

1. `pom.xml`：`maven.compiler.release` 17 → 21；
2. CI：全部 workflow `setup-java` 固定 `temurin 21`；
3. 容器：`deploy/Dockerfile` builder `maven:3.9-eclipse-temurin-21`、
   runtime `eclipse-temurin:21-jre`；
4. 不再承诺 JDK 17 运行兼容（记录为主动决策，不维护双版本）；
5. 验证：JDK 21 本地全量回归 0 failures + 真实 Runner 门禁 7/7。

## Alternatives

1. 保持 17：无法正式启用虚拟线程/新特性，POC 成果闲置；
2. 双版本矩阵（17/21）：CI 成本翻倍，项目无外部 API 消费者；
3. 仅 runtime 21、编译仍 17：虚拟线程反射路径继续存在，收益打折。

## Consequences

优点：虚拟线程正式可用、JDK 21 性能/内存特性、单版本 toolchain。

缺点：JDK 17 不再支持（显式记录）；镜像重新拉取（CI 首次稍慢）。

风险：JDK 21 下潜在时序/线程行为差异——由全量回归与真实 Runner
门禁覆盖；若出现 JDK 17 特有依赖可回滚（git revert 单点）。

## Implementation

`pom.xml`、5 个 workflow、`deploy/Dockerfile`；本 ADR 登记于
ROADMAP P4b（JDK 21 正式升级）。
