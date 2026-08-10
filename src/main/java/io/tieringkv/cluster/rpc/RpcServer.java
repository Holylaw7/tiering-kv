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
import io.tieringkv.cluster.rpc.security.RpcSecurityConfig;
import io.tieringkv.cluster.rpc.security.TokenBucket;

import java.net.InetSocketAddress;
import java.util.function.Function;

/** Netty RPC 服务端（ADR-0041）：解码帧 → 处理器 → 响应。 */
public final class RpcServer implements AutoCloseable {

    private final int port;
    private final RpcSecurityConfig security;
    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup();
    private volatile Function<RpcFrame, RpcFrame> handler;
    private volatile TokenBucket rateLimiter;
    private SslContext sslContext;
    private Channel channel;

    public RpcServer(int port) {
        this(port, RpcSecurityConfig.disabled());
    }

    public RpcServer(int port, RpcSecurityConfig security) {
        this.port = port;
        this.security = security;
    }

    public void start() throws InterruptedException {
        if (security.sslEnabled()) {
            try {
                sslContext = SslContextBuilder.forServer(
                                security.certFile().toFile(), security.keyFile().toFile())
                        .build();
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
                        if (security.authenticationEnabled()) {
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
            if (current == null) {
                ctx.close();
                return;
            }
            ctx.writeAndFlush(current.apply(request));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
