package io.tieringkv.cluster.gateway;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;

import java.net.InetSocketAddress;

/** 真实 TCP Redis Cluster 网关（ADR-0068）：Netty NIO + RESP2。 */
public final class NettyClusterGateway implements AutoCloseable {

    private final UnifiedClusterGateway gateway;
    private final GatewayMetricsRegistry metrics;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyClusterGateway(UnifiedClusterGateway gateway) {
        this(gateway, new GatewayMetricsRegistry());
    }

    public NettyClusterGateway(UnifiedClusterGateway gateway,
                               GatewayMetricsRegistry metrics) {
        this.gateway = gateway;
        this.metrics = metrics;
    }

    public void start(String host, int port) throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ClusterGatewayInitializer(gateway, metrics));
        serverChannel = bootstrap.bind(host, port).sync().channel();
    }

    public int boundPort() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public GatewayMetricsRegistry metrics() {
        return metrics;
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
