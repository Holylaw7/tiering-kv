package io.tieringkv.webconsole.domain;

/**
 * 恢复任务状态（ADR-0366）。
 *
 * <p>与 {@link BackupStatus} 分离：备份记录的 {@code status} 只描述"备份文件是否可用"，
 * 恢复的成败写入独立的 {@code restoreStatus}，避免一次失败的恢复让备份看起来也坏了。
 */
public enum RestoreStatus {
    /** 从未恢复过。 */
    NONE,
    /** 正在回放；此状态下同一实例拒绝新的恢复与备份任务。 */
    RESTORING,
    /** 全部条目回放成功。 */
    SUCCEEDED,
    /** 回放中途失败：已写入的键保留，{@code restoredKeys} 为已回放数。 */
    FAILED
}
