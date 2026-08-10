# 故障注入测试（Failure Injection）

Phase 14 · 2026-08-10

## 1. 覆盖矩阵

| 类别 | 注入 | 预期 |
| --- | --- | --- |
| 网络 | 延迟 | 提交变慢但成功 |
| 网络 | 断连（follower） | 半数存活仍提交 |
| 网络 | 丢包（30%） | 重试后收敛，无丢失 |
| 节点 | 杀 leader | 选举新 leader，已提交数据保留 |
| 存储 | 日志尾部损坏 | 恢复截断损坏尾部 |

## 2. 实现

- `FailureInjectionTest`：`FaultInjectingTransport` 注入延迟/断连/丢包；
- 节点故障：`suspend()+close()`；
- 存储故障：破坏 RaftLog 段文件 CRC 后重启恢复。

## 3. 结果（2026-08-10）

5/5 通过：延迟下提交成功、断连 follower 半数提交、30% 丢包 20 条全部
提交、杀 leader 后新 leader 提交新条目（旧数据保留）、损坏日志尾部截断。

## 4. 后续扩展

- 磁盘满/IO 错误注入；
- 跨机真实网络工具（tc netem）；
- 随机混沌测试（Chaos Monkey 风格）。
