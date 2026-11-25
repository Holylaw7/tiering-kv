package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.ConsoleLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/** 多副本协调租约锁仓储（M3）。 */
public interface ConsoleLeaseRepository extends JpaRepository<ConsoleLease, String> {

    @Modifying
    @Query("""
            UPDATE ConsoleLease l
            SET l.ownerId = :ownerId, l.leaseUntil = :leaseUntil, l.updatedAt = :now
            WHERE l.leaseKey = :leaseKey AND (l.leaseUntil < :now OR l.ownerId = :ownerId)
            """)
    int tryAcquireOrRenew(@Param("leaseKey") String leaseKey,
                          @Param("ownerId") String ownerId,
                          @Param("leaseUntil") LocalDateTime leaseUntil,
                          @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM ConsoleLease l WHERE l.leaseKey = :leaseKey AND l.ownerId = :ownerId")
    int release(@Param("leaseKey") String leaseKey, @Param("ownerId") String ownerId);
}
