package io.tieringkv.sql.distributed;

import io.tieringkv.sql.SqlEngine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 查询缓存（Goal 7）：queryId + 水位失效。 */
public final class QueryCache {

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(long watermark, List<SqlEngine.Row> rows) {
    }

    public List<SqlEngine.Row> get(String queryId, long watermark) {
        Entry entry = cache.get(queryId);
        return entry != null && entry.watermark() == watermark
                ? entry.rows() : null;
    }

    public void put(String queryId, long watermark,
                    List<SqlEngine.Row> rows) {
        cache.put(queryId, new Entry(watermark,
                List.copyOf(rows)));
    }

    public void invalidate(String queryId) {
        cache.remove(queryId);
    }

    public int size() {
        return cache.size();
    }
}
