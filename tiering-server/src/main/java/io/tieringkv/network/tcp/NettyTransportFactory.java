package io.tieringkv.network.tcp;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.Locale;

/**
 * Netty 传输层工厂（B1 优化）：
 * Linux 环境下自动探测并优先启用原生 Epoll 传输，Windows/macOS 或显式配置
 * {@code -Dtieringkv.transport=nio} 时无缝回退标准 NIO。
 */
public final class NettyTransportFactory {

    public static final String TRANSPORT_PROPERTY = "tieringkv.transport";

    private NettyTransportFactory() {
    }

    /** 是否应使用 Linux 原生 Epoll 传输。 */
    public static boolean useEpoll() {
        String mode = System.getProperty(TRANSPORT_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
        if ("nio".equals(mode)) {
            return false;
        }
        try {
            return Epoll.isAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 当前实际生效的传输名称（{@code epoll} 或 {@code nio}），便于观测与日志诊断。 */
    public static String transportName() {
        return useEpoll() ? "epoll" : "nio";
    }

    public static EventLoopGroup newBossGroup(int threads) {
        return useEpoll() ? new EpollEventLoopGroup(threads) : new NioEventLoopGroup(threads);
    }

    public static EventLoopGroup newWorkerGroup(int threads) {
        if (threads <= 0) {
            return useEpoll() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
        }
        return useEpoll() ? new EpollEventLoopGroup(threads) : new NioEventLoopGroup(threads);
    }

    public static Class<? extends ServerChannel> serverChannelClass() {
        return useEpoll() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }
}
