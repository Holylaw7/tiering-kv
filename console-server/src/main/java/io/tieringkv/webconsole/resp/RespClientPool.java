package io.tieringkv.webconsole.resp;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * 轻量连接池：按实例复用 TCP 连接，避免每次请求握手。
 *
 * <p>实现取向：无第三方依赖、失败即重建，池上限仅为防止异常场景无限增长。
 */
public final class RespClientPool implements AutoCloseable {

    private final Supplier<RespClient> factory;
    private final int maxIdle;
    private final Deque<RespClient> idle = new ArrayDeque<>();
    private boolean closed;

    public RespClientPool(Supplier<RespClient> factory, int maxIdle) {
        this.factory = factory;
        this.maxIdle = Math.max(1, maxIdle);
    }

    public RespClient borrow() throws IOException {
        synchronized (idle) {
            if (closed) {
                throw new RespException("连接池已关闭");
            }
            RespClient client;
            while ((client = idle.pollFirst()) != null) {
                if (client.isUsable()) {
                    return client;
                }
                client.close();
            }
        }
        return factory.get();
    }

    public void release(RespClient client) {
        if (client == null) {
            return;
        }
        if (!client.isUsable()) {
            client.close();
            return;
        }
        synchronized (idle) {
            if (!closed && idle.size() < maxIdle) {
                idle.addFirst(client);
                return;
            }
        }
        client.close();
    }

    public long idleCount() {
        synchronized (idle) {
            return idle.size();
        }
    }

    @Override
    public void close() {
        synchronized (idle) {
            closed = true;
            RespClient client;
            while ((client = idle.pollFirst()) != null) {
                client.close();
            }
        }
    }
}
