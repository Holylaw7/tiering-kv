package io.tieringkv.concurrency;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 同键请求合并（ADR-0025）：并发 GET 共享一次 loader；
 * 10000 请求 → 1 次存储读取。
 */
public final class RequestCoalescer {

    private final ConcurrentHashMap<ByteBuffer, CompletableFuture<byte[]>> inFlight =
            new ConcurrentHashMap<>();

    public byte[] coalesce(ByteBuffer key, Supplier<byte[]> loader) {
        CompletableFuture<byte[]> created = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            return existing.join();
        }
        try {
            byte[] value = loader.get();
            created.complete(value);
            return value;
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, created);
        }
    }
}
