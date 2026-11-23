package io.tieringkv.network.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.connection.ConnectionInitializer;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * TCP 服务端（Netty NIO，ADR-0003 / ADR-0006）：
 * 支持可选 TLS、maxclients 连接数上限与空闲读超时控制。
 */
public final class TieringKvServer implements AutoCloseable {

    private final ServerConfig config;
    private final CommandEngine engine;
    private final MetricsRegistry metrics;
    private final RuntimeOptions runtimeOptions;
    private final Supplier<SslContext> sslContextSupplier;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final ChannelGroup activeChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public TieringKvServer(ServerConfig config, CommandEngine engine) {
        this(config, engine, new MetricsRegistry());
    }

    public TieringKvServer(ServerConfig config, CommandEngine engine, MetricsRegistry metrics) {
        this(config, engine, metrics, null,
                new RuntimeOptions(config.maxClients(), config.idleTimeoutSeconds()));
    }

    public TieringKvServer(ServerConfig config, CommandEngine engine, MetricsRegistry metrics,
                           RuntimeOptions runtimeOptions) {
        this(config, engine, metrics, null, runtimeOptions);
    }

    public TieringKvServer(ServerConfig config, CommandEngine engine, MetricsRegistry metrics,
                           Supplier<SslContext> sslContextSupplier,
                           RuntimeOptions runtimeOptions) {
        this.config = config;
        this.engine = engine;
        this.metrics = metrics;
        this.runtimeOptions = runtimeOptions != null
                ? runtimeOptions
                : new RuntimeOptions(config.maxClients(), config.idleTimeoutSeconds());
        this.sslContextSupplier = sslContextSupplier != null
                ? sslContextSupplier
                : buildTlsSupplier(config.tls());
        this.runtimeOptions.setTimeoutListener(this::applyIdleTimeoutToActiveChannels);
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ConnectionInitializer(
                        engine,
                        metrics,
                        sslContextSupplier,
                        activeChannels,
                        activeConnections,
                        runtimeOptions::maxClients,
                        runtimeOptions::idleTimeoutSeconds));
        serverChannel = bootstrap.bind(config.host(), config.port()).sync().channel();
    }

    private void applyIdleTimeoutToActiveChannels(int seconds) {
        for (Channel ch : activeChannels) {
            if (!ch.isActive()) {
                continue;
            }
            ch.eventLoop().execute(() -> {
                if (!ch.isActive()) {
                    return;
                }
                ChannelPipeline pipeline = ch.pipeline();
                if (pipeline.get("idle-state") != null) {
                    pipeline.remove("idle-state");
                }
                if (seconds > 0) {
                    IdleStateHandler handler = new IdleStateHandler(seconds, 0, 0, TimeUnit.SECONDS);
                    if (pipeline.get("ssl") != null) {
                        pipeline.addAfter("ssl", "idle-state", handler);
                    } else {
                        pipeline.addFirst("idle-state", handler);
                    }
                }
            });
        }
    }

    private static Supplier<SslContext> buildTlsSupplier(ServerConfig.TlsConfig tls) {
        if (tls == null || !tls.enabled()) {
            return null;
        }
        try {
            SslContextBuilder builder = SslContextBuilder.forServer(
                    tls.certPath().toFile(), tls.keyPath().toFile());
            if (tls.caPath() != null) {
                builder.trustManager(tls.caPath().toFile());
            }
            builder.clientAuth(tls.requireClientAuth() ? ClientAuth.REQUIRE : ClientAuth.NONE);
            SslContext fixed = builder.build();
            return () -> fixed;
        } catch (SSLException e) {
            throw new IllegalStateException("failed to initialize RESP server TLS context", e);
        }
    }

    public int boundPort() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public RuntimeOptions runtimeOptions() {
        return runtimeOptions;
    }

    public void awaitTermination() throws InterruptedException {
        shutdownLatch.await();
    }

    public void stopAccepting() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
    }

    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        activeChannels.close().awaitUninterruptibly();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        shutdownLatch.countDown();
    }

    @Override
    public void close() {
        shutdown();
    }

    /** 运行期动态网络参数（供 CONFIG SET 与 TieringKvServer 共享）。 */
    public static final class RuntimeOptions {

        private final AtomicInteger maxClients;
        private final AtomicInteger idleTimeoutSeconds;
        private final AtomicReference<IntConsumer> timeoutListener = new AtomicReference<>();

        public RuntimeOptions(int maxClients, int idleTimeoutSeconds) {
            if (maxClients < 0 || idleTimeoutSeconds < 0) {
                throw new IllegalArgumentException("maxClients and idleTimeoutSeconds must be non-negative");
            }
            this.maxClients = new AtomicInteger(maxClients);
            this.idleTimeoutSeconds = new AtomicInteger(idleTimeoutSeconds);
        }

        public int maxClients() {
            return maxClients.get();
        }

        public void setMaxClients(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("maxClients must be >= 1");
            }
            maxClients.set(limit);
        }

        public int idleTimeoutSeconds() {
            return idleTimeoutSeconds.get();
        }

        public void setIdleTimeoutSeconds(int seconds) {
            if (seconds < 0) {
                throw new IllegalArgumentException("idleTimeoutSeconds must be >= 0");
            }
            idleTimeoutSeconds.set(seconds);
            IntConsumer listener = timeoutListener.get();
            if (listener != null) {
                listener.accept(seconds);
            }
        }

        void setTimeoutListener(IntConsumer listener) {
            this.timeoutListener.set(listener);
        }
    }
}
