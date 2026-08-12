# Coprocessor SQL 下推指南（ADR-0210）

## 使用

```java
CoprocessorRequest request = new CoprocessorRequest(
        Operator.FILTER, startKey, endKey, threshold, columns);
List<Row> result = new CoprocessorExecutor().execute(request, rows);
```

支持 FILTER / PROJECT / AGGREGATE；范围 [start, end)；
下推结果与上层 SQL 一致（谓词矩阵验收）。
