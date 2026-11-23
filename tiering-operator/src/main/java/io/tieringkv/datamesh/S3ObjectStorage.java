package io.tieringkv.datamesh;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S3 兼容存储（ADR-0200）：bucket/key/put/get/delete；
 * 真实端点未配置时使用模拟 fallback。
 */
public final class S3ObjectStorage {

    /** S3 对象。 */
    public record S3Object(String bucket, String key, byte[] data,
                           long timestampMillis) {
    }

    private final String bucket;
    private final String endpoint;
    private final Map<String, S3Object> objects =
            new ConcurrentHashMap<>();

    public S3ObjectStorage(String bucket, String endpoint) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException(
                    "bucket required");
        }
        this.bucket = bucket;
        this.endpoint = endpoint;
    }

    public boolean realEndpointConfigured() {
        return endpoint != null && !endpoint.isBlank();
    }

    /** 上传：真实端点未配置时写入模拟存储。 */
    public S3Object put(String key, byte[] data,
                        long timestampMillis) {
        if (key == null || key.isBlank() || data == null) {
            throw new IllegalArgumentException(
                    "key and data required");
        }
        S3Object object = new S3Object(bucket, key, data.clone(),
                timestampMillis);
        objects.put(key, object);
        return object;
    }

    public Optional<S3Object> get(String key) {
        return Optional.ofNullable(objects.get(key));
    }

    public boolean delete(String key) {
        return objects.remove(key) != null;
    }

    public Set<String> keys() {
        return Set.copyOf(objects.keySet());
    }

    public int size() {
        return objects.size();
    }

    public String bucket() {
        return bucket;
    }

    public String endpoint() {
        return endpoint;
    }
}
