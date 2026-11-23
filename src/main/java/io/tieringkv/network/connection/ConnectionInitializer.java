package io.tieringkv.network.connection;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.protocol.RespDecoder;
import io.tieringkv.protocol.RespEncoder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** 连接管道：[可选 TLS] → [可选空闲超时] → RESP 解码/编码 → 命令执行。 */
public final class ConnectionInitializer extends ChannelInitializer<SocketChannel> {

    private final CommandEngine engine;
    private final MetricsRegistry metrics;
    private final Supplier<SslContext> sslContextSupplier;
    private final ChannelGroup activeChannels;
    private final AtomicInteger activeConnections;
    private final IntSupplier maxClients;
    private final IntSupplier idleTimeoutSeconds;

    public ConnectionInitializer(CommandEngine engine, MetricsRegistry metrics) {
        this(engine, metrics, null, null, null, null, null);
    }

    public ConnectionInitializer(CommandEngine engine,
                                 MetricsRegistry metrics,
                                 Supplier<SslContext> sslContextSupplier,
                                 ChannelGroup activeChannels,
                                 AtomicInteger activeConnections,
                                 IntSupplier maxClients,
                                 IntSupplier idleTimeoutSeconds) {
        this.engine = engine;
        this.metrics = metrics;
        this.sslContextSupplier = sslContextSupplier;
        this.activeChannels = activeChannels;
        this.activeConnections = activeConnections;
        this.maxClients = maxClients;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        if (activeChannels != null) {
            activeChannels.add(channel);
        }
        SslContext sslContext = sslContextSupplier == null ? null : sslContextSupplier.get();
        if (sslContext != null) {
            channel.pipeline().addLast("ssl", sslContext.newHandler(channel.alloc()));
        }
        int timeout = idleTimeoutSeconds == null ? 0 : idleTimeoutSeconds.getAsInt();
        if (timeout > 0) {
            channel.pipeline().addLast("idle-state",
                    new IdleStateHandler(timeout, 0, 0, TimeUnit.SECONDS));
        }
        // 出站事件从调用点向 head 方向传播：Encoder 必须在写入方（command-handler）之前
        channel.pipeline().addLast("resp-encoder", new RespEncoder());
        channel.pipeline().addLast("resp-decoder", new RespDecoder());
        channel.pipeline().addLast("command-handler",
                new CommandHandler(engine, metrics, activeConnections, maxClients));
    }
}
