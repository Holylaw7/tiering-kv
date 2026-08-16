# ADR-0351: AtomicStringOps Decorator Chain (Production TTL Defect Fix)

## Status

Accepted

## Context

实践运行验证（本地 jar + RESP 原始协议）发现生产缺陷：

```text
SETEX ex 100 v   -> +OK
TTL ex           -> -1   （期望 ~100）
PTTL ex          -> -1
```

根因：命令层通过 `storage instanceof AtomicStringOps` 决定 TTL /
EXPIRE / PERSIST / INCR / APPEND / GETSET 等命令是否走原子路径；
`Main.java` 生产链为

```text
VectorIndexSyncStorageEngine
  -> TrackingStorageEngine
    -> TieringStorageEngine
      -> HotKeyStorageEngine
        -> WALStorageEngine（实现 AtomicStringOps）
          -> MemTable
```

四个装饰器只实现 `StorageEngine`，最外层不满足 instanceof，命令层
静默回退到 get+put：

- `TTL` 恒为 -1 / -2（TTL 已随 SETEX 写入底层，但读不到）；
- `INCR/APPEND/GETSET/GETDEL/SETNX` 退化为非原子实现（并发下
  存在丢失更新）；
- `EXPIRE/PERSIST` 退化为 get+put（功能可用但不原子）。

同类缺口：`ReplicatedStorageEngine`（Redis Cluster 网关数据面）
同样只实现 `StorageEngine`，集群路径 TTL 查询/原子命令退化；修复
需要 Raft 日志新增 ATOMIC 命令类型，独立登记为 TD-081。

## Decision

**引入装饰器基类并保持原子能力透传**：

1. 新增 `AbstractStorageDecorator implements StorageEngine,
   AtomicStringOps`：核心 StorageEngine 方法与全部 AtomicStringOps
   方法统一委托 delegate；delegate 不支持原子语义时显式抛出
   `UnsupportedOperationException`（禁止静默回退）；
2. 四个生产链装饰器（`HotKeyStorageEngine` /
   `TieringStorageEngine` / `TrackingStorageEngine` /
   `VectorIndexSyncStorageEngine`）改为继承基类，并各自覆写原子写
   操作注入横切逻辑：
   - HotKey：原子写前/后失效热读缓存；
   - Tiering：原子写前背压检查、写后水位记账；
   - Tracking：原子读写产生 AccessEvent（参与 LFU 热度统计）；
   - Vector：GETSET/GETDEL/SETNX/UPDATE 维护 VECTOR 索引生命周期；
3. 回归测试按 Main.java 相同装配（含 VectorIndexSync 外层）验证
   SETEX→TTL、EXPIRE→PERSIST、INCR/APPEND 保留 TTL、GETSET 清除
   TTL、热点化后原子写缓存失效、VECTOR 索引同步。

集群路径：`ReplicatedStorageEngine` 原子操作修复（Raft ATOMIC
命令 + apply 结果回传）不在本 ADR 范围内，登记 TD-081。

## Alternatives

1. 命令层改为 capability 探测（如 `storage.supportsAtomic()`）：
   仍无法让装饰器链动态实现接口，且扩大命令层与存储 SPI 耦合；
2. 在 Main.java 手动把最外层包一层 `AtomicAdapter`：只修单机入口，
   测试/集群/未来装饰器仍会踩坑，治标不治本；
3. 把 TTL 查询下沉到 `StorageEngine` 默认方法：污染核心 SPI，
   所有引擎（含冷层/复制层）被迫承担 TTL 语义。

## Consequences

优点：

- 命令层 SPI 纯度不变，装饰器对命令层透明保留原子语义；
- 后续新增装饰器只需继承基类，自动获得原子能力透传；
- 热缓存失效、背压、热度统计、向量索引同步覆盖原子写路径。

缺点：

- 装饰器必须继承基类（而非直接 implements StorageEngine），
  需要 javadoc 约定；
- delegate 不支持 AtomicStringOps 时原子命令显式失败——当前所有
  生产/测试链底层均为 MemTable / WALStorageEngine，无实际影响。

风险：

- 低。基类保持默认方法（applyBatch/removePhysical/clear 等）不动，
  批量路径仍经子类 put/delete 覆写分发，行为与改造前一致。

## Implementation

- 新增 `src/main/java/io/tieringkv/storage/
  AbstractStorageDecorator.java`；
- 修改 `HotKeyStorageEngine` / `TieringStorageEngine` /
  `TrackingStorageEngine` / `VectorIndexSyncStorageEngine`
  （继承基类 + 原子写横切）；
- 新增 `src/test/java/io/tieringkv/storage/
  ProductionChainAtomicOpsTest.java`；
- 本 ADR；CHANGELOG Unreleased；ROADMAP 技术债登记 TD-081。
