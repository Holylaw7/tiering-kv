package io.tieringkv.cluster.rpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Netty RPC 客户端（ADR-0041）：连接复用、请求关联、超时与幂等重试。
 */
public final class RpcClient implements AutoCloseable {

    private final EventLoopGroup group = new NioEventLoopGroup();
    private final Map<InetSocketAddress, Channel> channels = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<RpcFrame>> pending = new ConcurrentHashMap<>();

    /** 发送请求；连接失败/超时按 retries 次数重试（仅幂等消息）。 */
    public CompletableFuture<RpcFrame> call(InetSocketAddress address, RpcFrame frame,
                                            long timeoutMillis, int retries) {
        return doCall(address, frame, timeoutMillis, retries);
    }

    private CompletableFuture<RpcFrame> doCall(InetSocketAddress address, RpcFrame frame,
                                               long timeoutMillis, int retries) {
        CompletableFuture<RpcFrame> future = new CompletableFuture<>();
        pending.put(frame.requestId(), future);
        Channel channel = channel(address);
        if (channel == null || !channel.isActive()) {
            pending.remove(frame.requestId());
            future.completeExceptionally(new IllegalStateException("no connection to " + address));
        } else {
            channel.writeAndFlush(frame).addListener(f -> {
                if (!f.isSuccess()) {
                    pending.remove(frame.requestId());
                    future.completeExceptionally(f.cause());
                }
            });
        }
        future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        pending.remove(frame.requestId());
                    }
                });
        return future.handle((result, error) -> {
            if (error != null) {
                if (frame.type().idempotent() && retries > 0) {
                    return retry(address, frame, timeoutMillis, retries - 1);
                }
                return CompletableFuture.<RpcFrame>failedFuture(error);
            }
            return CompletableFuture.completedFuture(result);
        }).thenCompose(f -> f);
    }

    private CompletableFuture<RpcFrame> retry(InetSocketAddress address, RpcFrame frame,
                                              long timeoutMillis, int retries) {
        RpcFrame retryFrame = new RpcFrame(RequestId.next().value(),
                frame.type(), frame.payload());
        return doCall(address, retryFrame, timeoutMillis, retries);
    }

    private Channel channel(InetSocketAddress address) {
        Channel channel = channels.get(address);
        if (channel != null && channel.isActive()) {
            return channel;
        }
        synchronized (channels) {
            channel = channels.get(address);
            if (channel != null && channel.isActive()) {
                return channel;
            }
            try {
                Bootstrap bootstrap = new Bootstrap()
                        .group(group)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline()
                                        .addLast("decoder", new RpcCodec.Decoder())
                                        .addLast("encoder", new RpcCodec.Encoder())
                                        .addLast("handler", new ResponseHandler());
                            }
                        });
                channel = bootstrap.connect(address).syncUninterruptibly().channel();
                channels.put(address, channel);
            } catch (Exception e) {
                return null;
            }
        }
        return channel;
    }

    public int connectionCount() {
        return (int) channels.values().stream().filter(Channel::isActive).count();
    }

    public void close() {
        for (Channel channel : channels.values()) {
            channel.close().syncUninterruptibly();
        }
        group.shutdownGracefully();
    }

    private final class ResponseHandler extends SimpleChannelInboundHandler<RpcFrame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RpcFrame response) {
            CompletableFuture<RpcFrame> future = pending.remove(response.requestId());
            if (future != null) {
                future.complete(response);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
