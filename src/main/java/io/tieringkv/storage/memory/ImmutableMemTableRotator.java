package io.tieringkv.storage.memory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable MemTable 轮转（ADR-0204）：Active → Immutable → Flush。 */
public final class ImmutableMemTableRotator {

    private final AtomicLong sequence = new AtomicLong();
    private final Set<String> immutables =
            new CopyOnWriteArraySet<>();
    private volatile String activeId;

    public ImmutableMemTableRotator() {
        this.activeId = "mem-" + sequence.incrementAndGet();
    }

    /** 轮转：当前 active → immutable，创建新 active。 */
    public synchronized String rotate() {
        immutables.add(activeId);
        activeId = "mem-" + sequence.incrementAndGet();
        return activeId;
    }

    /** Flush 完成：移除 immutable。 */
    public synchronized boolean flushDone(String immutableId) {
        return immutables.remove(immutableId);
    }

    public int immutableCount() {
        return immutables.size();
    }

    public Set<String> immutables() {
        return Set.copyOf(immutables);
    }

    public String activeId() {
        return activeId;
    }

    public List<String> pendingFlush() {
        return List.copyOf(immutables);
    }
}
