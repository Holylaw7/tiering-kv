package io.tieringkv.operations;

import io.tieringkv.storage.types.ByteArrayKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阻塞命令通知（ADR-0293）：push 唤醒等待者；等待在命令执行线程
 * （事件循环外），不阻塞事件循环。
 */
public final class BlockingListNotifier {

    private static final Map<ByteArrayKey, Object> MONITORS =
            new ConcurrentHashMap<>();

    private BlockingListNotifier() {
    }

    public static void notifyPush(byte[] key) {
        Object monitor = MONITORS.get(new ByteArrayKey(key));
        if (monitor != null) {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
    }

    /** 等待推送通知；返回是否被唤醒（超时返回 false）。 */
    public static boolean awaitPush(byte[] key,
                                    long timeoutMillis) {
        Object monitor = MONITORS.computeIfAbsent(
                new ByteArrayKey(key), ignored -> new Object());
        synchronized (monitor) {
            try {
                if (timeoutMillis <= 0) {
                    monitor.wait();
                } else {
                    monitor.wait(timeoutMillis);
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
