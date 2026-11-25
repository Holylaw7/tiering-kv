package io.tieringkv.webconsole.repository;

import io.tieringkv.webconsole.domain.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/** 多副本共享登录限流仓储（M3）。 */
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, String> {

    @Modifying
    @Query("""
            UPDATE LoginAttempt a
            SET a.failCount = a.failCount + 1, a.updatedAt = :now
            WHERE a.bucketKey = :bucketKey AND a.expiresAt >= :now
            """)
    int incrementActive(@Param("bucketKey") String bucketKey, @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            UPDATE LoginAttempt a
            SET a.failCount = 1, a.windowStart = :now, a.expiresAt = :expiresAt, a.updatedAt = :now
            WHERE a.bucketKey = :bucketKey AND a.expiresAt < :now
            """)
    int resetExpired(@Param("bucketKey") String bucketKey,
                     @Param("now") LocalDateTime now,
                     @Param("expiresAt") LocalDateTime expiresAt);

    @Modifying
    @Query("DELETE FROM LoginAttempt a WHERE a.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
