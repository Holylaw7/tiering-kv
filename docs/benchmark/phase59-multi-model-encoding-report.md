# Phase 59 — v4 M2 Multi-Model Encoding Benchmark Report

## 口径

- 环境：本地 Windows / JDK 17 开发机基线；
- 20,000 轮编码：JSON（约 30 字节）、时序（64 点）、向量（64 维）；
- 只测编码路径（含 TK 类型前缀），不含 RESP 编码/网络。

## 结果

| 模型 | 编码吞吐 |
| --- | --- |
| JSON | 2,764,569 ops/s |
| TIME_SERIES（64 点） | 320,456 ops/s |
| VECTOR（64 维） | 646,074 ops/s |

## 结论

- 多模型值编码为微秒级路径，不构成瓶颈；
- 时序（每点 16B 二进制）体积远小于 JSON 文本方案；
- 类型字节 1–5 冻结不变，6/7/8 additive；
- RESP3 映射为纯内存转换，吞吐由上游命令路径主导。

## 复现

```bash
mvn -Dsurefire.excludedGroups= -Dtest=MultiModelEncodingBenchmarkTest \
  -DfailIfNoTests=false test
```
