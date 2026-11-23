package io.tieringkv.operations;

import io.tieringkv.storage.types.ByteArrayKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 阻塞命令通知（ADR-0293）：push 唤醒等待者；等待在命令执行线程/虚拟线程
 * （事件循环外），不阻塞事件循环，也不占用分片工作线程。
 */
public final class BlockingListNotifier {

    private static final Map<ByteArrayKey, CopyOnWriteArraySet<Object>> WAITERS =
            new ConcurrentHashMap<>();

    private BlockingListNotifier() {
    }

    public static void notifyPush(byte[] key) {
        CopyOnWriteArraySet<Object> waiters = WAITERS.get(new ByteArrayKey(key));
        if (waiters != null) {
            for (Object signal : waiters) {
                synchronized (signal) {
                    signal.notifyAll();
                }
            }
        }
    }

    /** 等待单个键的推送通知；返回是否未被中断（超时同样返回 true 以继续检查）。 */
    public static boolean awaitPush(byte[] key,
                                    long timeoutMillis) {
        return awaitAnyPush(List.of(key), timeoutMillis);
    }

    /** 同时等待任意给定键的推送通知；退出时自动摘除避免内存泄漏。 */
    public static boolean awaitAnyPush(List<byte[]> keys,
                                       long timeoutMillis) {
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        Object signal = new Object();
        List<ByteArrayKey> wrapped = new ArrayList<>(keys.size());
        for (byte[] key : keys) {
            ByteArrayKey bk = new ByteArrayKey(key);
            wrapped.add(bk);
            WAITERS.computeIfAbsent(bk, ignored -> new CopyOnWriteArraySet<>()).add(signal);
        }
        try {
            synchronized (signal) {
                if (timeoutMillis <= 0) {
                    signal.wait();
                } else {
                    signal.wait(timeoutMillis);
                }
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            for (ByteArrayKey bk : wrapped) {
                CopyOnWriteArraySet<Object> set = WAITERS.get(bk);
                if (set != null) {
                    set.remove(signal);
                    if (set.isEmpty()) {
                        WAITERS.remove(bk, set);
                    }
                }
            }
        }
    }
}
