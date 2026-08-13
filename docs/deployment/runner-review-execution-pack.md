# Runner Review Execution Pack

## 执行

```bash
./scripts/runner-review.sh target/runner-review
```

## 清单

TD-048 / TD-049 / K8S-001 / BM-001 / BM-002 / TD-076：

1. 配置远程 + Linux Runner；
2. 逐项执行（脚本/工作流引用）；
3. 归档证据（${gate}.evidence）；
4. 更新 GateConvergenceV17：SEALED_GA → CLOSED。
