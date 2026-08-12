# 对象存储冷层归档指南（ADR-0194）

## 使用

```java
ObjectStorageArchive archive = new ObjectStorageArchive(policy,
        "aws-us");
ArchivedObject object = archive.upload(snapshot, now);
Optional<ArchivedObject> restored = archive.download(object.objectKey());
archive.delete(object.objectKey());
```

跨驻留归档默认拒绝（SecurityException）；归档保持 stale 语义。
