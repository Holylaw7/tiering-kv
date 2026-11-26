package io.tieringkv.webconsole.web.dto;

import io.tieringkv.webconsole.domain.BackupScheduleMode;

import java.time.LocalDateTime;

/** 立即触发定时备份策略的执行回执（v6.1.0 A2）。 */
public record BackupScheduleRunResult(
        Long scheduleId,
        BackupScheduleMode mode,
        String status,
        String message,
        Long backupRecordId,
        LocalDateTime executedAt) {
}
