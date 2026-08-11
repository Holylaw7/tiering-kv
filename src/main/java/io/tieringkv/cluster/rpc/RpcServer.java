package io.tieringkv.cluster.rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.tieringkv.cluster.rpc.security.RpcAuthInterceptor;
import io.tieringkv.cluster.rpc.security.HmacAuthInterceptor;
import io.tieringkv.cluster.rpc.security.HmacConfig;
import io.tieringkv.cluster.rpc.security.NonceCache;
import io.tieringkv.cluster.rpc.security.RpcTlsConfig;
import io.tieringkv.cluster.rpc.security.TlsMode;
import io.tieringkv.cluster.rpc.security.RpcSecurityConfig;
import io.tieringkv.cluster.rpc.security.TokenBucket;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Netty RPC 服务端（ADR-0041）：解码帧 → 处理器 → 响应。 */
public final class RpcServer implements AutoCloseable {

    private final int port;
    private final RpcSecurityConfig security;
    private final RpcTlsConfig tlsConfig;
    private final HmacConfig hmacConfig;
    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup();
    private volatile Function<RpcFrame, RpcFrame> handler;
    private volatile Function<RpcFrame, CompletableFuture<RpcFrame>>
            asyncHandler;
    private volatile TokenBucket rateLimiter;
    private SslContext sslContext;
    private Channel channel;

    public RpcServer(int port) {
        this(port, RpcSecurityConfig.disabled());
    }

    public RpcServer(int port, RpcSecurityConfig security) {
        this(port, security, null, null);
    }

    public RpcServer(int port, RpcSecurityConfig security,
                     RpcTlsConfig tlsConfig, HmacConfig hmacConfig) {
        this.port = port;
        this.security = security;
        this.tlsConfig = tlsConfig;
        this.hmacConfig = hmacConfig;
    }

    public void start() throws InterruptedException {
        if (security.sslEnabled() || tlsConfig != null) {
            try {
                Path cert = tlsConfig != null ? tlsConfig.serverCertFile()
                        : security.certFile();
                Path key = tlsConfig != null ? tlsConfig.serverKeyFile()
                        : security.keyFile();
                SslContextBuilder builder = SslContextBuilder.forServer(
                        cert.toFile(), key.toFile());
                if (tlsConfig != null && tlsConfig.mode() == TlsMode.MUTUAL) {
                    builder.trustManager(tlsConfig.caFile().toFile())
                            .clientAuth(io.netty.handler.ssl.ClientAuth.REQUIRE);
                }
                sslContext = builder.build();
            } catch (Exception e) {
                throw new IllegalStateException("failed to load TLS certificate", e);
            }
        }
        if (security.rateLimitEnabled()) {
            rateLimiter = new TokenBucket(security.rateLimitQps());
        }
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (sslContext != null) {
                            ch.pipeline().addLast("ssl", sslContext.newHandler(ch.alloc()));
                        }
                        ch.pipeline()
                                .addLast("decoder", new RpcCodec.Decoder())
                                .addLast("encoder", new RpcCodec.Encoder());
                        if (hmacConfig != null) {
                            ch.pipeline().addLast("auth", new HmacAuthInterceptor(
                                    hmacConfig, NonceCache.defaults()));
                        } else if (security.authenticationEnabled()) {
                            ch.pipeline().addLast("auth", new RpcAuthInterceptor(
                                    security.authToken(), security.authExpiryMillis()));
                        }
                        ch.pipeline().addLast("handler", new Handler());
                    }
                });
        channel = bootstrap.bind(port).sync().channel();
    }

    public void handler(Function<RpcFrame, RpcFrame> handler) {
        this.handler = handler;
    }

    /** 异步处理器（ADR-0099）：长耗时操作（如 Raft 提案）不阻塞事件循环。 */
    public void asyncHandler(
            Function<RpcFrame, CompletableFuture<RpcFrame>> handler) {
        this.asyncHandler = handler;
    }

    public int boundPort() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        boss.shutdownGracefully();
        worker.shutdownGracefully();
    }

    private final class Handler extends SimpleChannelInboundHandler<RpcFrame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RpcFrame request) {
            TokenBucket bucket = rateLimiter;
            if (bucket != null && !bucket.tryAcquire()) {
                ctx.writeAndFlush(new RpcFrame(request.requestId(),
                        RpcMessageType.ERROR, RpcAuthInterceptor.RATE_LIMIT));
                return;
            }
            Function<RpcFrame, RpcFrame> current = handler;
            Function<RpcFrame, CompletableFuture<RpcFrame>> async =
                    asyncHandler;
            if (current == null && async == null) {
                ctx.close();
                return;
            }
            if (async != null) {
                CompletableFuture<RpcFrame> response;
                try {
                    response = async.apply(request);
                } catch (Throwable error) {
                    ctx.writeAndFlush(errorFrame(request, error));
                    return;
                }
                response.whenComplete((frame, error) -> {
                    if (error != null || frame == null) {
                        ctx.writeAndFlush(errorFrame(request, error));
                    } else {
                        ctx.writeAndFlush(frame);
                    }
                });
                return;
            }
            ctx.writeAndFlush(current.apply(request));
        }

        private static RpcFrame errorFrame(RpcFrame request, Throwable error) {
            String message = error == null ? "async handler failure"
                    : error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
            return new RpcFrame(request.requestId(), RpcMessageType.ERROR,
                    message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
