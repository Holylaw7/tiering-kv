package io.tieringkv.webconsole.domain;

/** 定时备份策略执行模式（v6.1.0 A2）。 */
public enum BackupScheduleMode {
    /** 逻辑导出：经 SCAN 流式压缩导出（.jsonl.gz / .jsonl）并落盘本地或 S3。 */
    LOGICAL,
    /** 物理快照：触发数据面 BGSAVE 落盘（单机 WAL/SSTable 或分布式多 Region 原子快照）。 */
    CHECKPOINT
}
