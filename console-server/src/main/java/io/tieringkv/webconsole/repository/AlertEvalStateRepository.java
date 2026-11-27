package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.AlertEvalState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/** 告警 HA 持久化状态仓储（Wave 2A H2）。 */
public interface AlertEvalStateRepository extends JpaRepository<AlertEvalState, String> {

    /**
     * 纯 ANSI SQL 单条 INSERT（规避 assigned-ID 实体在 saveAndFlush/merge 下产生的多余 SELECT 与乐观锁异常），
     * 并发主键冲突时由 MySQL 8 / H2 确定性抛出 DataIntegrityViolationException。
     */
    @Modifying
    @Query(value = """
            INSERT INTO alert_eval_state (state_key, satisfied_since, last_notified_at, updated_at)
            VALUES (:stateKey, :satisfiedSince, :lastNotifiedAt, :updatedAt)
            """, nativeQuery = true)
    int insertRaw(@Param("stateKey") String stateKey,
                  @Param("satisfiedSince") LocalDateTime satisfiedSince,
                  @Param("lastNotifiedAt") LocalDateTime lastNotifiedAt,
                  @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 原子抢占静默窗口（CAS）：仅当尚未通知或距上次通知已满静默时长时更新时间戳并返回 1，
     * 多副本并发执行同一告警推送时保证仅 1 个副本胜出。
     */
    @Modifying
    @Query("""
            UPDATE AlertEvalState s
            SET s.lastNotifiedAt = :now, s.updatedAt = :now
            WHERE s.stateKey = :stateKey
              AND (s.lastNotifiedAt IS NULL OR s.lastNotifiedAt <= :cutoff)
            """)
    int tryClaimNotificationWindow(@Param("stateKey") String stateKey,
                                   @Param("now") LocalDateTime now,
                                   @Param("cutoff") LocalDateTime cutoff);

    /** 长持续告警定期续刷 updated_at，避免活跃状态被 7 天孤儿清理误删。 */
    @Modifying
    @Query("UPDATE AlertEvalState s SET s.updatedAt = :now WHERE s.stateKey = :stateKey AND s.updatedAt < :staleBefore")
    int touchUpdatedAt(@Param("stateKey") String stateKey,
                       @Param("now") LocalDateTime now,
                       @Param("staleBefore") LocalDateTime staleBefore);

    @Modifying
    @Query("DELETE FROM AlertEvalState s WHERE s.stateKey LIKE :pattern")
    int deleteByKeyPattern(@Param("pattern") String pattern);

    @Modifying
    @Query("DELETE FROM AlertEvalState s WHERE s.updatedAt < :before")
    int deleteStaleBefore(@Param("before") LocalDateTime before);
}
