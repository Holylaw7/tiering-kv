package io.tieringkv.protocol;

/**
 * 连接级协议状态（ADR-0281）：默认 RESP2；HELLO 3 后切换 RESP3。
 * Phase 53 全接线到网络管道（本阶段供编码器/测试使用）。
 */
public final class ConnectionProtocolState {

    private volatile RespVersion version = RespVersion.RESP2;

    public RespVersion version() {
        return version;
    }

    public void setVersion(RespVersion version) {
        if (version == null) {
            throw new IllegalArgumentException(
                    "version required");
        }
        this.version = version;
    }

    public boolean isResp3() {
        return version == RespVersion.RESP3;
    }
}
