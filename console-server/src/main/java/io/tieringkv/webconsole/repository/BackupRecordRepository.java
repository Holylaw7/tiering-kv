package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.RestoreStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

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

    /** 某实例的全部记录（保留策略按实例计算，ADR-0367）。 */
    List<BackupRecord> findByInstanceId(Long instanceId);

    /** 超过保留期的失败记录。 */
    List<BackupRecord> findByStatusAndCreatedAtBefore(BackupStatus status, LocalDateTime threshold);

    /** 已被认领但尚未删完的记录（崩溃后重试）。 */
    List<BackupRecord> findByDeleteState(String deleteState);

    /**
     * 认领一次删除：条件更新保证多实例下只有一个进程拿到删除权（ADR-0367）。
     *
     * @return 1 表示认领成功，0 表示已被他人认领或记录状态不允许删除
     */
    /**
     * 认领删除（原生 SQL）。
     *
     * <p>用原生 SQL 而不是 JPQL：枚举参数在 JPQL 中的绑定方式依赖 Hibernate 的推断，
     * 曾出现把 RESTORING 当序数比较、导致正在恢复的备份被误删的情况（ADR-0367 实测）。
     * 这里直接与数据库中的字符串比较，语义明确。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE backup_record SET delete_state = 'RETIRING', delete_claimed_at = :now "
            + "WHERE id = :id AND delete_state IS NULL AND status = 'SUCCESS' "
            + "AND restore_status <> 'RESTORING'", nativeQuery = true)
    int claimDeletion(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 回收认领超时的记录（进程崩溃后由其他实例接手）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BackupRecord b set b.deleteState = null, b.deleteClaimedAt = null "
            + "where b.deleteState = 'RETIRING' and b.deleteClaimedAt < :staleBefore")
    int releaseStaleClaims(@Param("staleBefore") LocalDateTime staleBefore);

    /** 文件名是否仍被任何记录引用（孤儿文件判定）。 */
    boolean existsByFileName(String fileName);
}