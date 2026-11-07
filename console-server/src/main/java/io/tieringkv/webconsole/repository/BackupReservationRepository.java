package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.BackupReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 容量预留仓储（ADR-0369）。 */
public interface BackupReservationRepository extends JpaRepository<BackupReservation, Long> {

    Optional<BackupReservation> findFirstByBackupRecordIdAndStatusOrderByIdDesc(Long recordId, String status);

    @Query("select coalesce(sum(r.reservedBytes), 0) from BackupReservation r "
            + "where r.storageKey = :key and r.status = 'RESERVED'")
    long sumReserved(@Param("key") String key);

    List<BackupReservation> findByStatusAndExpiresAtBefore(String status, LocalDateTime before);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BackupReservation r set r.status = 'EXPIRED' "
            + "where r.status = 'RESERVED' and r.expiresAt < :before")
    int expireStale(@Param("before") LocalDateTime before);

    @Query("select coalesce(sum(r.reservedBytes), 0) from BackupReservation r where r.status = 'RESERVED'")
    long sumAllReserved();
}
