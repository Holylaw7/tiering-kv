package io.tieringkv;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.InMemoryKVStore;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.network.tcp.TieringKvServer;

/** Tiering-KV 入口：默认监听 0.0.0.0:6379。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig("0.0.0.0", 6379);
        TieringKvServer server = new TieringKvServer(
                config,
                new CommandEngine(CommandRegistry.createDefault(), new InMemoryKVStore()));
        server.start();
        System.out.println("Tiering-KV listening on " + server.boundPort());
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }
}
