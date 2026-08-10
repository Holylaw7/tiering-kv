package io.tieringkv.cluster.rpc.security;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;

import java.nio.charset.StandardCharsets;

/**
 * RPC 认证拦截器（ADR-0046）：连接建立后必须先发送 AUTH 帧
 * （payload = "token|expiryMillis"）；未认证的其他帧被拒绝。
 */
public final class RpcAuthInterceptor extends ChannelInboundHandlerAdapter {

    public static final AttributeKey<Boolean> AUTHENTICATED =
            AttributeKey.valueOf("rpcAuthenticated");
    public static final byte[] AUTH_REQUIRED =
            "ERR AUTH_REQUIRED".getBytes(StandardCharsets.UTF_8);
    public static final byte[] RATE_LIMIT =
            "ERR RATE_LIMIT".getBytes(StandardCharsets.UTF_8);

    private final String expectedToken;
    private final long expiryMillis;

    public RpcAuthInterceptor(String expectedToken, long expiryMillis) {
        this.expectedToken = expectedToken;
        this.expiryMillis = expiryMillis;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof RpcFrame frame)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (frame.type() == RpcMessageType.AUTH) {
            boolean valid = verify(frame.payload());
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
                    AUTH_REQUIRED));
            return;
        }
        ctx.fireChannelRead(msg);
    }

    private boolean verify(byte[] payload) {
        String value = new String(payload, StandardCharsets.UTF_8);
        int separator = value.indexOf('|');
        if (separator <= 0) {
            return false;
        }
        String token = value.substring(0, separator);
        long expiry;
        try {
            expiry = Long.parseLong(value.substring(separator + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        return token.equals(expectedToken) && expiry > System.currentTimeMillis();
    }
}
