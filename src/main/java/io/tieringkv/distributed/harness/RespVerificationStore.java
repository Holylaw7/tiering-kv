package io.tieringkv.distributed.harness;

import java.io.IOException;

/** 真实 RESP/TCP 验证存储：连接 Tiering-KV 服务器执行 GET/SET。 */
public final class RespVerificationStore
        implements VerificationStore, AutoCloseable {

    private final RespClient client;

    public RespVerificationStore(String host, int port)
            throws IOException {
        this.client = new RespClient(host, port);
    }

    @Override
    public String get(String key) {
        try {
            return client.get(key);
        } catch (IOException e) {
            throw new IllegalStateException("GET failed", e);
        }
    }

    @Override
    public void put(String key, String value) {
        try {
            client.put(key, value);
        } catch (IOException e) {
            throw new IllegalStateException("SET failed", e);
        }
    }

    public void close() throws IOException {
        client.close();
    }
}
