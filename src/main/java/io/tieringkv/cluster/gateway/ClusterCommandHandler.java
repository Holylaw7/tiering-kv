package io.tieringkv.cluster.gateway;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.command.RespCommand;
import io.tieringkv.command.RespRequestParser;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespEncoder;
import io.tieringkv.protocol.RespProtocolException;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;

import java.util.ArrayList;
import java.util.List;

/** 集群网关命令处理器（ADR-0068）：RESP 命令 → 统一路由网关 → 写回。 */
public final class ClusterCommandHandler extends ChannelInboundHandlerAdapter {

    private final UnifiedClusterGateway gateway;
    private final GatewayMetricsRegistry metrics;
    private final List<RespValue> pending = new ArrayList<>();
    private boolean asking;

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
            RespValue response = handleCommand(command);
            metrics.recordRequest(System.nanoTime() - t0);
            pending.add(response);
        } catch (RespProtocolException e) {
            pending.add(RespError.protocol(e.getMessage()));
        }
    }

    /**
     * ASK 迁移语义（TD-038）：ASKING 命令置位，仅对下一条命令生效
     * （Redis single-shot 语义）。
     */
    RespValue handleCommand(RespCommand command) {
        if ("asking".equalsIgnoreCase(command.name())) {
            asking = true;
            return new RespSimpleString("OK");
        }
        RespValue response = gateway.executeWithAsking(
                command.name(), command.args(), asking);
        asking = false;
        return response;
    }

    /** 单个读批次解码完成后：批量编码 + 单次 flush（吞吐关键，ADR-0068）。 */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        if (pending.isEmpty()) {
            return;
        }
        ByteBuf buffer = ctx.alloc().buffer();
        for (RespValue response : pending) {
            RespEncoder.write(buffer, response);
        }
        pending.clear();
        ctx.writeAndFlush(buffer);
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
