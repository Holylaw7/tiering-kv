package io.tieringkv.network.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.network.connection.ConnectionInitializer;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

/**
 * TCP 服务端（Netty NIO，ADR-0003 / ADR-0006）。
 * boss 接受连接，worker 处理 IO；命令在连接事件循环内同步执行（Phase 1）。
 */
public final class TieringKvServer implements AutoCloseable {

    private final ServerConfig config;
    private final CommandEngine engine;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public TieringKvServer(ServerConfig config, CommandEngine engine) {
        this.config = config;
        this.engine = engine;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ConnectionInitializer(engine));
        serverChannel = bootstrap.bind(config.host(), config.port()).sync().channel();
    }

    /** 实际绑定端口（配置 port=0 时用于测试获取随机端口）。 */
    public int boundPort() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** 阻塞直到 {@link #shutdown()} 被调用。 */
    public void awaitTermination() throws InterruptedException {
        shutdownLatch.await();
    }

    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
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
}
