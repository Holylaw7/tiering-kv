package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.OpLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/** 审计日志仓储。 */
public interface OpLogRepository extends JpaRepository<OpLog, Long> {

    Page<OpLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<OpLog> findByActionContainingOrderByCreatedAtDesc(String action, Pageable pageable);

    @Modifying
    @Query("delete from OpLog o where o.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
