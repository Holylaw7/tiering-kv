package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
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
}
