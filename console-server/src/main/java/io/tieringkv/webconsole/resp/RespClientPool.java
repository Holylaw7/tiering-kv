package io.tieringkv.webconsole.resp;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 有界 RESP 连接池（M3-B3）：限制最大并发总连接数（maxTotal + 借用超时）、
 * 空闲存活上限（maxIdleTimeMs）与严格借还许可跟踪，防并发打爆目标实例。
 */
public final class RespClientPool implements AutoCloseable {

    private record IdleEntry(RespClient client, long idleSinceMillis) {
    }

    private final Supplier<RespClient> factory;
    private final int maxIdle;
    private final int maxTotal;
    private final long borrowTimeoutMs;
    private final long maxIdleTimeMs;
    private final Semaphore permits;
    private final Deque<IdleEntry> idle = new ArrayDeque<>();
    private final Set<RespClient> borrowed = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean closed;

    public RespClientPool(Supplier<RespClient> factory, int maxIdle) {
        this(factory, maxIdle, Math.max(maxIdle * 4, 16), 5_000L, 60_000L);
    }

    public RespClientPool(Supplier<RespClient> factory, int maxIdle, int maxTotal,
                          long borrowTimeoutMs, long maxIdleTimeMs) {
        this.factory = factory;
        this.maxIdle = Math.max(1, maxIdle);
        this.maxTotal = Math.max(this.maxIdle, maxTotal);
        this.borrowTimeoutMs = Math.max(10L, borrowTimeoutMs);
        this.maxIdleTimeMs = Math.max(0L, maxIdleTimeMs);
        this.permits = new Semaphore(this.maxTotal);
    }

    public RespClient borrow() throws IOException {
        if (closed) {
            throw new RespException("连接池已关闭");
        }
        boolean acquired;
        try {
            acquired = permits.tryAcquire(borrowTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RespException("等待连接池许可被中断");
        }
        if (!acquired) {
            throw new RespException("实例连接池已满（maxTotal=" + maxTotal + "），借用超时");
        }

        RespClient candidate = null;
        long now = System.currentTimeMillis();
        synchronized (idle) {
            if (closed) {
                permits.release();
                throw new RespException("连接池已关闭");
            }
            IdleEntry entry;
            while ((entry = idle.pollFirst()) != null) {
                boolean expired = maxIdleTimeMs > 0 && (now - entry.idleSinceMillis()) > maxIdleTimeMs;
                if (!expired && entry.client().isUsable()) {
                    candidate = entry.client();
                    borrowed.add(candidate);
                    break;
                }
                entry.client().close();
            }
        }
        if (candidate != null) {
            return candidate;
        }
        try {
            RespClient created = factory.get();
            synchronized (idle) {
                if (closed) {
                    created.close();
                    permits.release();
                    throw new RespException("连接池已关闭");
                }
                borrowed.add(created);
            }
            return created;
        } catch (Throwable ex) {
            permits.release();
            if (ex instanceof IOException io) {
                throw io;
            }
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new RespException("建立 RESP 连接失败：" + ex.getMessage());
        }
    }

    public void release(RespClient client) {
        if (client == null) {
            return;
        }
        boolean owned;
        boolean parked = false;
        long now = System.currentTimeMillis();
        synchronized (idle) {
            owned = borrowed.remove(client);
            if (!closed && client.isUsable() && idle.size() < maxIdle) {
                idle.addFirst(new IdleEntry(client, now));
                parked = true;
            }
        }
        if (owned) {
            permits.release();
        }
        if (!parked) {
            client.close();
        }
    }

    /** 销毁异常连接并归还并发许可，防止半读/断开连接泄露连接池配额。 */
    public void discard(RespClient client) {
        if (client == null) {
            return;
        }
        boolean owned;
        synchronized (idle) {
            owned = borrowed.remove(client);
        }
        if (owned) {
            permits.release();
        }
        client.close();
    }

    public int activeCount() {
        synchronized (idle) {
            return borrowed.size();
        }
    }

    public long idleCount() {
        synchronized (idle) {
            return idle.size();
        }
    }

    @Override
    public void close() {
        Deque<IdleEntry> toClose;
        synchronized (idle) {
            closed = true;
            toClose = new ArrayDeque<>(idle);
            idle.clear();
        }
        toClose.forEach(entry -> entry.client().close());
    }
}
