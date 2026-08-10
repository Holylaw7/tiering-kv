package io.tieringkv.cluster.rpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.tieringkv.cluster.rpc.security.RpcSecurityConfig;
import io.tieringkv.cluster.rpc.security.HmacConfig;
import io.tieringkv.cluster.rpc.security.HmacToken;
import io.tieringkv.cluster.rpc.security.RpcTlsConfig;
import io.tieringkv.cluster.rpc.security.TlsMode;
import io.tieringkv.cluster.rpc.security.NonceCache;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Netty RPC 客户端（ADR-0041/0046）：连接复用、请求关联、超时与幂等重试；
 * 连接建立与重试全程非阻塞（绝不在事件循环线程上同步 connect）。
 */
public final class RpcClient implements AutoCloseable {

    private final EventLoopGroup group = new NioEventLoopGroup();
    private final RpcSecurityConfig security;
    private final RpcTlsConfig tlsConfig;
    private final HmacConfig hmacConfig;
    private final Map<InetSocketAddress, Channel> channels = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, CompletableFuture<Channel>> connecting =
            new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<RpcFrame>> pending = new ConcurrentHashMap<>();

    public RpcClient() {
        this(RpcSecurityConfig.disabled());
    }

    public RpcClient(RpcSecurityConfig security) {
        this(security, null, null);
    }

    public RpcClient(RpcSecurityConfig security, RpcTlsConfig tlsConfig, HmacConfig hmacConfig) {
        this.security = security;
        this.tlsConfig = tlsConfig;
        this.hmacConfig = hmacConfig;
    }

    /** 发送请求；连接失败/超时按 retries 次数重试（仅幂等消息）。 */
    public CompletableFuture<RpcFrame> call(InetSocketAddress address, RpcFrame frame,
                                            long timeoutMillis, int retries) {
        return doCall(address, frame, timeoutMillis, retries);
    }

    private CompletableFuture<RpcFrame> doCall(InetSocketAddress address, RpcFrame frame,
                                               long timeoutMillis, int retries) {
        return channelAsync(address).thenCompose(channel ->
                sendAndWait(address, channel, frame, timeoutMillis, retries));
    }

    private CompletableFuture<RpcFrame> sendAndWait(InetSocketAddress address, Channel channel,
                                                    RpcFrame frame, long timeoutMillis,
                                                    int retries) {
        CompletableFuture<RpcFrame> future = new CompletableFuture<>();
        pending.put(frame.requestId(), future);
        channel.writeAndFlush(frame).addListener(f -> {
            if (!f.isSuccess()) {
                pending.remove(frame.requestId());
                future.completeExceptionally(f.cause());
            }
        });
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

    /** 异步获取活跃连接；无连接时发起异步 connect（不阻塞调用线程/事件循环）。 */
    private CompletableFuture<Channel> channelAsync(InetSocketAddress address) {
        Channel channel = channels.get(address);
        if (channel != null && channel.isActive()) {
            return CompletableFuture.completedFuture(channel);
        }
        CompletableFuture<Channel> pendingConnect = connecting.get(address);
        if (pendingConnect != null) {
            return pendingConnect;
        }
        synchronized (connecting) {
            pendingConnect = connecting.get(address);
            if (pendingConnect != null) {
                return pendingConnect;
            }
            pendingConnect = connect(address);
            connecting.put(address, pendingConnect);
            pendingConnect.whenComplete((c, e) -> connecting.remove(address));
            return pendingConnect;
        }
    }

    private CompletableFuture<Channel> connect(InetSocketAddress address) {
        CompletableFuture<Channel> future = new CompletableFuture<>();
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            if (security.sslEnabled() || tlsConfig != null) {
                                try {
                                    SslContextBuilder builder = SslContextBuilder.forClient();
                                    if (tlsConfig != null) {
                                        Path trust = tlsConfig.mode() == TlsMode.MUTUAL
                                                ? tlsConfig.caFile()
                                                : tlsConfig.serverCertFile();
                                        builder.trustManager(trust.toFile());
                                        if (tlsConfig.mode() == TlsMode.MUTUAL) {
                                            builder.keyManager(
                                                    tlsConfig.clientCertFile().toFile(),
                                                    tlsConfig.clientKeyFile().toFile());
                                        }
                                    } else {
                                        builder.trustManager(security.certFile().toFile());
                                    }
                                    SslContext clientContext = builder.build();
                                    ch.pipeline().addLast("ssl",
                                            clientContext.newHandler(ch.alloc(),
                                                    address.getHostName(), address.getPort()));
                                } catch (Exception e) {
                                    throw new IllegalStateException(
                                            "failed to load TLS trust store", e);
                                }
                            }
                            ch.pipeline()
                                    .addLast("decoder", new RpcCodec.Decoder())
                                    .addLast("encoder", new RpcCodec.Encoder())
                                    .addLast("handler", new ResponseHandler());
                            if (hmacConfig != null) {
                                ch.pipeline().addLast("authSender",
                                        new ChannelInboundHandlerAdapter() {
                                            @Override
                                            public void channelActive(ChannelHandlerContext ctx) {
                                                String token = HmacToken.issue(
                                                        hmacConfig.clientId(),
                                                        System.currentTimeMillis(),
                                                        Long.toHexString(
                                                                io.tieringkv.cluster.rpc.RequestId
                                                                        .next().value()),
                                                        hmacConfig.keys().get(0));
                                                ctx.writeAndFlush(new RpcFrame(
                                                        io.tieringkv.cluster.rpc.RequestId
                                                                .next().value(),
                                                        RpcMessageType.AUTH,
                                                        token.getBytes(
                                                                java.nio.charset.StandardCharsets.UTF_8)));
                                                ctx.fireChannelActive();
                                            }
                                        });
                            } else if (security.authenticationEnabled()) {
                                ch.pipeline().addLast("authSender",
                                        new ChannelInboundHandlerAdapter() {
                                            @Override
                                            public void channelActive(ChannelHandlerContext ctx) {
                                                String payload = security.authToken() + "|"
                                                        + security.authExpiryMillis();
                                                ctx.writeAndFlush(new RpcFrame(
                                                        RequestId.next().value(),
                                                        RpcMessageType.AUTH,
                                                        payload.getBytes(
                                                                java.nio.charset.StandardCharsets.UTF_8)));
                                                ctx.fireChannelActive();
                                            }
                                        });
                            }
                            // connect 必须在 authSender 之后：channelActive 先发 AUTH 再完成连接
                            ch.pipeline().addLast("connect",
                                    new ConnectHandler(address, future));
                        }
                    });
            bootstrap.connect(address).addListener(f -> {
                if (!f.isSuccess()) {
                    future.completeExceptionally(f.cause());
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
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

    private final class ConnectHandler extends ChannelInboundHandlerAdapter {
        private final InetSocketAddress address;
        private final CompletableFuture<Channel> future;

        private ConnectHandler(InetSocketAddress address, CompletableFuture<Channel> future) {
            this.address = address;
            this.future = future;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            channels.put(address, ctx.channel());
            future.complete(ctx.channel());
            ctx.fireChannelActive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            future.completeExceptionally(cause);
            ctx.close();
        }
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
