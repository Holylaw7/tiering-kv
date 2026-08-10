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

import java.net.InetSocketAddress;
import java.util.function.Function;

/** Netty RPC 服务端（ADR-0041）：解码帧 → 处理器 → 响应。 */
public final class RpcServer implements AutoCloseable {

    private final int port;
    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup();
    private volatile Function<RpcFrame, RpcFrame> handler;
    private Channel channel;

    public RpcServer(int port) {
        this.port = port;
    }

    public void start() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("decoder", new RpcCodec.Decoder())
                                .addLast("encoder", new RpcCodec.Encoder())
                                .addLast("handler", new Handler());
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
