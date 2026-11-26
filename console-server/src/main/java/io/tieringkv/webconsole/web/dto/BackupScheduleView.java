package io.tieringkv.webconsole.web.dto;

import io.tieringkv.webconsole.domain.BackupSchedule;
import io.tieringkv.webconsole.domain.BackupScheduleMode;

import java.time.LocalDateTime;

/** 定时备份策略只读视图（v6.1.0 A2）。 */
public record BackupScheduleView(
        Long id,
        Long instanceId,
        String instanceName,
        String name,
        String cronExpression,
        BackupScheduleMode mode,
        int retentionCount,
        boolean enabled,
        LocalDateTime lastRunAt,
        LocalDateTime nextRunAt,
        String lastStatus,
        String lastMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static BackupScheduleView of(BackupSchedule schedule, String instanceName) {
        return new BackupScheduleView(
                schedule.getId(),
                schedule.getInstanceId(),
                instanceName,
                schedule.getName(),
                schedule.getCronExpression(),
                schedule.getMode(),
                schedule.getRetentionCount(),
                schedule.isEnabled(),
                schedule.getLastRunAt(),
                schedule.getNextRunAt(),
                schedule.getLastStatus(),
                schedule.getLastMessage(),
                schedule.getCreatedAt(),
                schedule.getUpdatedAt());
    }
}
