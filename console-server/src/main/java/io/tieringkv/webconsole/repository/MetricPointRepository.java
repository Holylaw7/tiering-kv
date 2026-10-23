package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.MetricPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 指标仓储。 */
public interface MetricPointRepository extends JpaRepository<MetricPoint, Long> {

    Optional<MetricPoint> findFirstByInstanceIdOrderByCollectedAtDesc(Long instanceId);

    List<MetricPoint> findByInstanceIdAndCollectedAtBetweenOrderByCollectedAtAsc(
            Long instanceId, LocalDateTime from, LocalDateTime to);

    @Modifying
    @Query("delete from MetricPoint m where m.collectedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
