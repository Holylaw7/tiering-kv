package io.tieringkv.cluster.rpc.security;

/** TLS 模式（ADR-0051）：单向 / 双向。 */
public enum TlsMode {
    ONE_WAY,
    MUTUAL
}
