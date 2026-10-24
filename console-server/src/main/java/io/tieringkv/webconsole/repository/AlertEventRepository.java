package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.AlertEvent;
import io.tieringkv.webconsole.domain.AlertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 告警事件仓储。 */
public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    Page<AlertEvent> findAllByOrderByFiredAtDesc(Pageable pageable);

    List<AlertEvent> findByStatusOrderByFiredAtDesc(AlertStatus status);

    Optional<AlertEvent> findFirstByRuleIdAndInstanceIdAndStatusOrderByFiredAtDesc(
            Long ruleId, Long instanceId, AlertStatus status);

    long countByStatus(AlertStatus status);

    long countByStatusAndFiredAtAfter(AlertStatus status, LocalDateTime after);
}
