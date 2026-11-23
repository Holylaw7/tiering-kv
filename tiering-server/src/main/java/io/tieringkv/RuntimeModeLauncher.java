package io.tieringkv;

/**
 * 扩展运行模式启动器 SPI（C2 模块解耦）：单机入口 {@link Main} 仅依赖核心服务层，
 * 存在集群模块时自动通过 {@link java.util.ServiceLoader} 分发 {@code cluster-node} / {@code gateway} 模式。
 */
public interface RuntimeModeLauncher {

    boolean supports(String mode);

    void launch(String mode, String[] args) throws Exception;
}
