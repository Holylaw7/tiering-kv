package io.tieringkv;

import io.tieringkv.runtime.GatewayRuntime;
import io.tieringkv.runtime.TxnRuntimeMain;

/**
 * 集群与网关模式 SPI 提供者（驻留 {@code tiering-cluster} 模块）。
 */
public final class ClusterRuntimeModeLauncher implements RuntimeModeLauncher {

    @Override
    public boolean supports(String mode) {
        return "cluster-node".equals(mode) || "gateway".equals(mode);
    }

    @Override
    public void launch(String mode, String[] args) throws Exception {
        if ("cluster-node".equals(mode)) {
            ClusterMain.main(args);
            return;
        }
        if ("gateway".equals(mode)) {
            GatewayRuntime.start(TxnRuntimeMain.parse(args));
            return;
        }
        throw new IllegalArgumentException("unsupported --mode: " + mode);
    }
}
