package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.BackupSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/** 定时备份策略仓储（v6.1.0 A2）。 */
public interface BackupScheduleRepository extends JpaRepository<BackupSchedule, Long> {

    List<BackupSchedule> findAllByOrderByIdDesc();

    List<BackupSchedule> findByInstanceIdOrderByIdDesc(Long instanceId);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<BackupSchedule> findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(LocalDateTime now);
}
