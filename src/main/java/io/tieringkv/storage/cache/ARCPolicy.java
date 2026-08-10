package io.tieringkv.storage.cache;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * ARC 原型（ADR-0012）：T1（近期）/ T2（高频）+ B1/B2 ghost，p 自适应。
 * 插入序 = LRU 序；EVICT 事件把淘汰键移入对应 ghost，感知"误淘汰再访问"。
 */
public final class ARCPolicy implements EvictionPolicy {

    private final int capacity;
    private final LinkedHashMap<ByteBuffer, Long> t1 = new LinkedHashMap<>();
    private final LinkedHashMap<ByteBuffer, Long> t2 = new LinkedHashMap<>();
    private final LinkedHashSet<ByteBuffer> b1 = new LinkedHashSet<>();
    private final LinkedHashSet<ByteBuffer> b2 = new LinkedHashSet<>();
    private int p;

    public ARCPolicy(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public String name() {
        return "arc";
    }

    @Override
    public synchronized void onAccess(AccessEvent event) {
        ByteBuffer key = ByteBuffer.wrap(event.key());
        if (event.operation() == AccessEvent.AccessOperation.DELETE) {
            t1.remove(key);
            t2.remove(key);
            b1.remove(key);
            b2.remove(key);
            return;
        }
        if (event.operation() == AccessEvent.AccessOperation.EVICT) {
            if (t1.remove(key) != null) {
                addGhost(b1, key);
            } else if (t2.remove(key) != null) {
                addGhost(b2, key);
            }
            return;
        }

        long now = event.timestamp();
        if (t1.containsKey(key)) {
            t1.remove(key);
            t2.put(key, now);
        } else if (t2.containsKey(key)) {
            t2.remove(key);
            t2.put(key, now);
        } else if (b1.contains(key)) {
            p = Math.min(capacity, p + Math.max(1, b1.size() > 0 ? b2.size() / b1.size() : b2.size()));
            b1.remove(key);
            t2.put(key, now);
        } else if (b2.contains(key)) {
            p = Math.max(0, p - Math.max(1, b2.size() > 0 ? b1.size() / b2.size() : b1.size()));
            b2.remove(key);
            t2.put(key, now);
        } else {
            onMiss(key, now);
        }
    }

    @Override
    public synchronized EvictionCandidate selectCandidate() {
        if (!t1.isEmpty() && t1.size() > p) {
            return candidate(firstEntry(t1));
        }
        if (!t2.isEmpty()) {
            return candidate(firstEntry(t2));
        }
        if (!t1.isEmpty()) {
            return candidate(firstEntry(t1));
        }
        return null;
    }

    private void onMiss(ByteBuffer key, long now) {
        if (t1.size() + b1.size() == capacity) {
            if (t1.size() < capacity) {
                removeFirst(b1);
            } else {
                removeFirstMap(t1); // 永久淘汰（无 ghost）
            }
        } else if (t1.size() + t2.size() + b1.size() + b2.size() >= capacity) {
            if (t1.size() + t2.size() + b1.size() + b2.size() == 2 * capacity) {
                removeFirst(b2);
            }
            replace();
        }
        t1.put(key, now);
    }

    /** 标准 ARC REPLACE：|T1| &gt; p 时淘汰 T1 LRU → B1，否则 T2 LRU → B2。 */
    private void replace() {
        if (t1.size() > p) {
            ByteBuffer victim = firstKey(t1);
            t1.remove(victim);
            addGhost(b1, victim);
        } else {
            ByteBuffer victim = firstKey(t2);
            t2.remove(victim);
            addGhost(b2, victim);
        }
    }

    private void addGhost(LinkedHashSet<ByteBuffer> ghost, ByteBuffer key) {
        ghost.add(key);
        while (b1.size() + b2.size() > capacity) {
            if (!b1.isEmpty()) {
                removeFirst(b1);
            } else {
                removeFirst(b2);
            }
        }
    }

    private static void removeFirst(LinkedHashSet<ByteBuffer> set) {
        var iterator = set.iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static void removeFirstMap(LinkedHashMap<ByteBuffer, Long> map) {
        var iterator = map.keySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static ByteBuffer firstKey(LinkedHashMap<ByteBuffer, Long> map) {
        return map.keySet().iterator().next();
    }

    private static Map.Entry<ByteBuffer, Long> firstEntry(LinkedHashMap<ByteBuffer, Long> map) {
        return map.entrySet().iterator().next();
    }

    private static EvictionCandidate candidate(Map.Entry<ByteBuffer, Long> entry) {
        ByteBuffer keyBuffer = entry.getKey().duplicate();
        byte[] key = new byte[keyBuffer.remaining()];
        keyBuffer.get(key);
        return new EvictionCandidate(key, 0, entry.getValue(), 0, 0);
    }

    int t1Size() {
        return t1.size();
    }

    int t2Size() {
        return t2.size();
    }

    int b1Size() {
        return b1.size();
    }

    int b2Size() {
        return b2.size();
    }

    int targetP() {
        return p;
    }
}
