package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.BackupRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 备份记录仓储。 */
public interface BackupRecordRepository extends JpaRepository<BackupRecord, Long> {

    List<BackupRecord> findAllByOrderByCreatedAtDesc();

    List<BackupRecord> findByInstanceIdOrderByCreatedAtDesc(Long instanceId);
}
