package io.tieringkv.storage.memory;

/**
 * 键空间过期通知回调 SPI（C2 模块解耦）：由存储层触发，服务器层通过 {@link java.util.ServiceLoader} 挂载发布实现。
 */
@FunctionalInterface
public interface KeyspaceExpiryHook {

    void publishExpired(byte[] key);
}
