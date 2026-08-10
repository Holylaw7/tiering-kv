package io.tieringkv.cluster.gateway;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.command.RespCommand;
import io.tieringkv.command.RespRequestParser;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespProtocolException;
import io.tieringkv.protocol.RespValue;

/** 集群网关命令处理器（ADR-0068）：RESP 命令 → 统一路由网关 → 写回。 */
public final class ClusterCommandHandler extends ChannelInboundHandlerAdapter {

    private final UnifiedClusterGateway gateway;
    private final GatewayMetricsRegistry metrics;

    public ClusterCommandHandler(UnifiedClusterGateway gateway,
                                 GatewayMetricsRegistry metrics) {
        this.gateway = gateway;
        this.metrics = metrics;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        metrics.connectionOpened();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        metrics.connectionClosed();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof RespValue value)) {
            ctx.fireChannelRead(msg);
            return;
        }
        try {
            RespCommand command = RespRequestParser.parse(value);
            long t0 = System.nanoTime();
            RespValue response = gateway.execute(command.name(), command.args());
            metrics.recordRequest(System.nanoTime() - t0);
            ctx.writeAndFlush(response);
        } catch (RespProtocolException e) {
            ctx.writeAndFlush(RespError.protocol(e.getMessage()));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Throwable root = cause;
        if (cause instanceof DecoderException && cause.getCause() != null) {
            root = cause.getCause();
        }
        if (root instanceof RespProtocolException e) {
            ctx.writeAndFlush(RespError.protocol(e.getMessage()))
                    .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
            return;
        }
        ctx.close();
    }
}
