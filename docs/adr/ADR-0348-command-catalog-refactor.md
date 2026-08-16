# ADR-0348: Command Catalog Refactor (Table-Driven Registry)

## Status

Accepted

## Context

`CommandRegistry.build` 内联约 130 个命令实例化（默认注册表冻结
132 = 130 + exec + command），命令数据与注册逻辑耦合在一个方法；
新增/调整命令需在大列表中手工定位，扩展命令（向量/JSON/TS）在
另一处手工构建。

## Decision

**命令目录表驱动**：

1. 新增 `CommandCatalog`（`io.tieringkv.command`）：`defaults()`
   返回默认命令表（130 项，每次调用新建实例，行为与原 `List.of`
   完全等价）；
2. `CommandRegistry.build` 遍历 `CommandCatalog.defaults()` 注册，
   删除内联 130 行；动态 `exec`/`command` 追加逻辑保留；
3. 扩展命令（向量/JSON/TS）保持 `createDefaultWithVectorAndMetrics`
   显式构建（依赖 registry/metrics 注入，不进默认表）；
4. 冻结契约不变：默认注册表 size 仍为 132（ReleaseV36/V37 等
   测试回归保护）。

## Alternatives

1. 注解/反射扫描命令类：启动期反射成本 + 隐式注册，破坏显式性；
2. 配置文件声明：失去编译期类型安全；
3. 保持内联：不解决可维护性。

## Consequences

优点：命令数据与构建逻辑分离、目录可测试（唯一性/计数）、新增
命令集中声明。

缺点：新增一个类；默认表仍为手写列表（后续可按需升级为 Spec +
arity/group 元数据）。

风险：低——纯结构性重构，全量回归与冻结计数测试兜底。

## Implementation

新增 `command/CommandCatalog`、`command/CommandCatalogTest`；修改
`CommandRegistry.build`（遍历 catalog）。
