# Jepsen-style Verification Harness

## 运行

```bash
java -cp target/tiering-kv.jar io.tieringkv.distributed.harness.VerificationHarness 4 200
```

输出 `HARNESS operations=... linearizable=...`；不可线性化退出码 1。

## 组成

- 历史生成器（并发 PUT/GET + 时间戳）；
- 结果校验器（LinearizabilityChecker 接线）；
- 报告输出；网络分区注入接口预留。
