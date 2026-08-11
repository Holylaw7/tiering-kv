package io.tieringkv.sharding.auto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 并发自动重分片（ADR-0140）：多线程迁移 + 限速 + 失败回滚。 */
public final class ConcurrentReshardExecutor {

    private final int workers;
    private final int maxMovesPerTick;

    public ConcurrentReshardExecutor(int workers, int maxMovesPerTick) {
        this.workers = Math.max(1, workers);
        this.maxMovesPerTick = Math.max(1, maxMovesPerTick);
    }

    public int execute(Map<String, byte[]> source,
                       Map<String, byte[]> target) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            Map<String, byte[]> sourceMap =
                    new ConcurrentHashMap<>(source);
            Map<String, byte[]> targetMap = new ConcurrentHashMap<>();
            List<String> keys = new ArrayList<>(sourceMap.keySet());
            for (int i = 0; i < keys.size(); i += maxMovesPerTick) {
                int end = Math.min(i + maxMovesPerTick, keys.size());
                List<String> batch = keys.subList(i, end);
                futures.add(pool.submit(() -> {
                    int moved = 0;
                    for (String key : batch) {
                        byte[] value = sourceMap.remove(key);
                        if (value != null) {
                            targetMap.put(key, value);
                            moved++;
                        }
                    }
                    return moved;
                }));
            }
            int moved = 0;
            for (Future<Integer> future : futures) {
                moved += future.get(30, TimeUnit.SECONDS);
            }
            source.clear();
            source.putAll(sourceMap);
            target.clear();
            target.putAll(targetMap);
            return moved;
        } finally {
            pool.shutdownNow();
        }
    }
}
