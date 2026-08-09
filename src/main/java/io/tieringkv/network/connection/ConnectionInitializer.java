package io.tieringkv.network.connection;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.protocol.RespDecoder;
import io.tieringkv.protocol.RespEncoder;

/** 连接管道：RESP 解码 → 命令执行 → RESP 编码（ADR-0006）。 */
public final class ConnectionInitializer extends ChannelInitializer<SocketChannel> {

    private final CommandEngine engine;

    public ConnectionInitializer(CommandEngine engine) {
        this.engine = engine;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        // 出站事件从调用点向 head 方向传播：Encoder 必须在写入方（command-handler）之前
        channel.pipeline().addLast("resp-encoder", new RespEncoder());
        channel.pipeline().addLast("resp-decoder", new RespDecoder());
        channel.pipeline().addLast("command-handler", new CommandHandler(engine));
    }
}
