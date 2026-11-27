package io.tieringkv.webconsole.domain;

/** 逻辑备份归档完整性校验状态（v6.4.0 D1）。 */
public enum BackupVerifyStatus {
    /** 历史归档尚未执行过校验。 */
    UNVERIFIED,
    /** SHA-256 摘要与 Gzip/JSONL 结构均校验通过。 */
    VERIFIED,
    /** 摘要不匹配、压缩帧损坏或结构畸形。 */
    CORRUPTED
}
