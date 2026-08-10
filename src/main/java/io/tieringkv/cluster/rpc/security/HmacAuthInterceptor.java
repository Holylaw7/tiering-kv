package io.tieringkv.cluster.rpc.security;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;

import java.nio.charset.StandardCharsets;

/** HMAC 认证拦截器（ADR-0051）：校验签名/时间窗口/防重放。 */
public final class HmacAuthInterceptor extends ChannelInboundHandlerAdapter {

    public static final AttributeKey<Boolean> AUTHENTICATED =
            AttributeKey.valueOf("hmacAuthenticated");

    private final HmacConfig config;
    private final NonceCache nonces;

    public HmacAuthInterceptor(HmacConfig config, NonceCache nonces) {
        this.config = config;
        this.nonces = nonces;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof RpcFrame frame)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (frame.type() == RpcMessageType.AUTH) {
            String token = new String(frame.payload(), StandardCharsets.UTF_8);
            boolean valid = HmacToken.verify(token, config, nonces, System.currentTimeMillis());
            ctx.channel().attr(AUTHENTICATED).set(valid);
            ctx.writeAndFlush(new RpcFrame(frame.requestId(), RpcMessageType.AUTH_RESPONSE,
                    new byte[]{(byte) (valid ? 1 : 0)}));
            if (!valid) {
                ctx.close();
            }
            return;
        }
        if (!Boolean.TRUE.equals(ctx.channel().attr(AUTHENTICATED).get())) {
            ctx.writeAndFlush(new RpcFrame(frame.requestId(), RpcMessageType.ERROR,
                    RpcAuthInterceptor.AUTH_REQUIRED));
            return;
        }
        ctx.fireChannelRead(msg);
    }
}
