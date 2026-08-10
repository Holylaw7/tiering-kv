package io.tieringkv.cluster.gateway;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.protocol.RespDecoder;
import io.tieringkv.protocol.RespEncoder;

/** 网关连接管道：RESP 编码 → 解码 → 集群命令处理（ADR-0068）。 */
public final class ClusterGatewayInitializer extends ChannelInitializer<SocketChannel> {

    private final UnifiedClusterGateway gateway;
    private final GatewayMetricsRegistry metrics;

    public ClusterGatewayInitializer(UnifiedClusterGateway gateway,
                                     GatewayMetricsRegistry metrics) {
        this.gateway = gateway;
        this.metrics = metrics;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline().addLast("resp-encoder", new RespEncoder());
        channel.pipeline().addLast("resp-decoder", new RespDecoder());
        channel.pipeline().addLast("cluster-handler",
                new ClusterCommandHandler(gateway, metrics));
    }
}
