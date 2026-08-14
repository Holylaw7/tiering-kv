package io.tieringkv.testkit;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内安全测试端口分配器（端口 TOCTOU 系统修复）。
 *
 * <p>背景：14613 个测试共享 OS 端口空间，传统 {@code freePort()}（探测后立即
 * 关闭）在"探测释放"到"endpoint bind"之间可能被同 JVM 其他测试线程占用，
 * 导致间歇性 {@code BindException}（GitHub 共享 runner 实测）。
 *
 * <p>机制：探测到端口后在本进程内保留 60s（远超 bind 前窗口），其他分配请求
 * 跳过保留端口；保留项按 TTL 惰性清理，集合有界，不会耗尽端口空间。
 */
public final class TestPorts {

    private static final Map<Integer, Long> RESERVED =
            new ConcurrentHashMap<>();
    private static final long RESERVATION_TTL_MILLIS = 60_000;

    private TestPorts() {
    }

    public static int freePort() throws IOException {
        long now = System.currentTimeMillis();
        RESERVED.entrySet().removeIf(entry ->
                now - entry.getValue() > RESERVATION_TTL_MILLIS);
        for (int i = 0; i < 200; i++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                int port = socket.getLocalPort();
                if (RESERVED.putIfAbsent(port, now) == null) {
                    return port;
                }
            }
        }
        throw new IOException("no reservable free port after 200 attempts");
    }
}
