package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.RestoreStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 备份记录仓储。 */
public interface BackupRecordRepository extends JpaRepository<BackupRecord, Long> {

    List<BackupRecord> findAllByOrderByCreatedAtDesc();

    List<BackupRecord> findByInstanceIdOrderByCreatedAtDesc(Long instanceId);

    /** 同一实例最近一条指定状态的记录（TD-091：并发备份检测）。 */
    Optional<BackupRecord> findFirstByInstanceIdAndStatusOrderByCreatedAtDesc(
            Long instanceId, BackupStatus status);

    /** 同一实例最近一条处于指定恢复状态的记录（ADR-0366：恢复互斥）。 */
    Optional<BackupRecord> findFirstByInstanceIdAndRestoreStatusOrderByRestoreStartedAtDesc(
            Long instanceId, RestoreStatus restoreStatus);

    /** 同一实例最近一条备份成功的记录（恢复入口的前置条件）。 */
    Optional<BackupRecord> findFirstByInstanceIdAndStatusOrderByFinishedAtDesc(
            Long instanceId, BackupStatus status);
}