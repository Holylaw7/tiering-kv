package io.tieringkv.lifecycle;

import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.WALManager;

import java.io.IOException;

/**
 * 优雅停机（ADR-0034）：停 accept → 排空活跃请求 → WAL force + checkpoint →
 * 关闭服务。幂等。
 */
public final class ShutdownManager implements AutoCloseable {

    private final TieringKvServer server;
    private final MetricsRegistry metrics;
    private final WALManager wal;
    private final MemTable memTable;
    private final long drainTimeoutMillis;
    private volatile boolean shutdown;

    public ShutdownManager(
            TieringKvServer server,
            MetricsRegistry metrics,
            WALManager wal,
            MemTable memTable,
            long drainTimeoutMillis) {
        this.server = server;
        this.metrics = metrics;
        this.wal = wal;
        this.memTable = memTable;
        this.drainTimeoutMillis = drainTimeoutMillis;
    }

    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        metrics.stopAccepting();
        server.stopAccepting();
        long deadline = System.currentTimeMillis() + drainTimeoutMillis;
        while (metrics.snapshot().activeRequests() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            wal.flushAndForce();
            wal.checkpoint(memTable);
        } catch (IOException e) {
            // 停机路径不抛：尽力持久化
        }
        server.shutdown();
    }

    @Override
    public void close() {
        shutdown();
    }
}
