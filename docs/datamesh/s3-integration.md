# 真实 S3 集成指南（ADR-0200）

## 使用

```java
S3ObjectStorage storage = new S3ObjectStorage("tiering",
        endpoint); // 空 endpoint → 模拟 fallback
storage.realEndpointConfigured(); // 是否真实端点
S3Object object = storage.put("obj-1", data, now);
Optional<S3Object> fetched = storage.get("obj-1");
storage.delete("obj-1");
```

真实端点未配置时自动降级模拟存储；数据克隆防篡改。
